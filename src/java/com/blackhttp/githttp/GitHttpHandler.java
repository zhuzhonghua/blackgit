package com.blackhttp.githttp;

import com.black.FetchRequestParser;
import com.black.Log;
import com.black.ShallowRequest;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;

public final class GitHttpHandler extends ChannelInboundHandlerAdapter {

    private final RepoResolver resolver;
    private final Config config;

    private HttpRequest request;
    private String method;
    private String path;
    private String requestUrl;
    private boolean keepAlive;
    private boolean bodyless;
    private File repoDir;
    private String pendingEndpoint;
    private SpooledBuffer inBody;
    /** Decoded username from the client's Authorization: Basic header; null until authed. */
    private String authnUser;
    /** Raw Authorization header value, forwarded to origin verbatim. */
    private String authnAuthz;

    public GitHttpHandler(RepoResolver resolver, Config config) {
        this.resolver = resolver;
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof HttpRequest) {
                onRequest(ctx, (HttpRequest) msg);
            } else if (msg instanceof HttpContent) {
                onContent(ctx, (HttpContent) msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void onRequest(ChannelHandlerContext ctx, HttpRequest req) {
        request = req;
        method = req.method().name();
        keepAlive = HttpUtil.isKeepAlive(req);
        bodyless = false;
        repoDir = null;

        String uri = req.uri();
        int q = uri.indexOf('?');
        path = q < 0 ? uri : uri.substring(0, q);
        String query = q < 0 ? null : uri.substring(q + 1);
        String host = req.headers().get(HOST);
        if (host == null) {
            SocketAddress local = ctx.channel().localAddress();
            host = local == null ? "localhost" : local.toString();
        }
        requestUrl = (config.tls ? "https" : "http") + "://" + host + uri;
        Log.logger.debug("HTTP {} {} from {} keepAlive={} query={}",
                method, requestUrl, ctx.channel().remoteAddress(), keepAlive, query);

        String repoName = RepoResolver.repoName(path);
        if (repoName == null) {
            Log.logger.warn("no repository segment in path {} -> 404", path);
            writeResponse(ctx, GitResponse.error(NOT_FOUND, "no repository in path: " + path));
            return;
        }
        File dir = resolver.resolve(repoName);
        if (dir == null || !dir.isDirectory()) {
            Log.logger.warn("repository {} not found at {} -> 404", repoName, dir);
            writeResponse(ctx, GitResponse.error(NOT_FOUND, "no such repository: " + repoName));
            return;
        }
        repoDir = dir;
        Log.logger.info("repo {} resolved to {}", repoName, dir);

        String endpoint = endpoint(path);
        if (endpoint == null) {
            writeResponse(ctx, GitResponse.error(NOT_FOUND, "not found"));
            return;
        }

        boolean isGet = HttpMethod.GET.name().equals(method)
                || HttpMethod.HEAD.name().equals(method);
        boolean isPost = HttpMethod.POST.name().equals(method);
        boolean isDelete = "DELETE".equals(method);
        boolean isInfoRefs = "info/refs".equals(endpoint);
        boolean isUploadPack = "git-upload-pack".equals(endpoint);
        boolean isReceivePack = "git-receive-pack".equals(endpoint);
        boolean isLock = "lock".equals(endpoint);

        // Only smart-git endpoints and the lock API require authentication;
        // unknown paths just 404.
        if (!((isGet && (isInfoRefs || isLock))
                || (isPost && (isUploadPack || isReceivePack || isLock))
                || (isDelete && isLock))) {
            writeResponse(ctx, GitResponse.error(NOT_FOUND, "not found"));
            return;
        }

        // --- Authenticate: require Authorization: Basic, reject anonymous ---
        String authz = req.headers().get("Authorization");
        String user = parseBasicUser(authz);
        if (user == null) {
            writeResponse(ctx, GitResponse.unauthorized(
                    "authentication required: send "
                    + "'Authorization: Basic <base64(user:token)>'"));
            return;
        }
        authnUser = user;
        authnAuthz = authz;
        Log.logger.info("authn user={} {} remote={}", user, requestUrl,
                ctx.channel().remoteAddress());
        // --- end authentication ---

        // --- File lock API: GET / POST / DELETE <repo>/lock?path=xxx ---
        if (isLock) {
            String lockPath = queryParameter(req, "path");
            com.black.FileLocks locks = new com.black.FileLocks(dir);
            if (isGet) {
                if (lockPath != null && !lockPath.isEmpty()) {
                    com.black.FileLocks.Lock l = locks.get(lockPath);
                    if (l == null) {
                        writeResponse(ctx, GitResponse.raw(
                                HttpResponseStatus.OK, "application/json",
                                ("{\"path\":\"" + lockPath + "\",\"locked\":false}")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    } else {
                        writeResponse(ctx, GitResponse.raw(
                                HttpResponseStatus.OK, "application/json",
                                ("{\"path\":\"" + lockPath + "\",\"locked\":true,"
                                        + "\"user\":\"" + l.user + "\",\"since\":\""
                                        + l.since + "\"}")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                } else {
                    writeResponse(ctx, GitResponse.raw(
                            HttpResponseStatus.OK, "application/json",
                            com.black.FileLocksJson.toJson(locks.list())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                return;
            }
            if (lockPath == null || lockPath.isEmpty()) {
                writeResponse(ctx, GitResponse.error(BAD_REQUEST, "missing ?path="));
                return;
            }
            if (isPost) {
                String r = locks.lock(lockPath, user);
                if ("ok".equals(r)) {
                    writeResponse(ctx, GitResponse.raw(
                            HttpResponseStatus.OK, "application/json",
                            ("{\"path\":\"" + lockPath + "\",\"locked\":true,"
                                    + "\"user\":\"" + user + "\"}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                } else {
                    writeResponse(ctx, GitResponse.error(CONFLICT,
                            r.replace("already-locked:", "already locked by ")));
                }
                return;
            }
            if (isDelete) {
                String r = locks.unlock(lockPath, user);
                if ("ok".equals(r)) {
                    writeResponse(ctx, GitResponse.raw(
                            HttpResponseStatus.OK, "application/json",
                            ("{\"path\":\"" + lockPath + "\",\"locked\":false}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                } else if ("not-locked".equals(r)) {
                    writeResponse(ctx, GitResponse.error(NOT_FOUND, "not locked"));
                } else {
                    writeResponse(ctx, GitResponse.error(FORBIDDEN,
                            r.replace("not-holder:", "not the holder: ")));
                }
                return;
            }
        }
        // --- end lock API ---

        if (isGet) {
                bodyless = HttpMethod.HEAD.name().equals(method);
                String service = queryParameter(req, "service");
                boolean v2 = wantsV2(req);
                boolean shallowHint = hasQueryParameter(req, "shallow");
                Log.logger.debug("info/refs request service={} protocolV2={} shallowHint={}",
                        service, v2, shallowHint);
                writeResponse(ctx, new InfoRefsService(dir, config)
                    .advertise(service, v2, shallowHint, user, authz));
            return;
        }

        // POST git-upload-pack / git-receive-pack: buffer the body, then dispatch.
            inBody = new SpooledBuffer(config.spoolMemoryLimit);
            pendingEndpoint = endpoint;
    }

    private void onContent(ChannelHandlerContext ctx, HttpContent content) throws IOException {
        if (inBody != null) {
            ByteBuf data = content.content();
            if (data.isReadable()) {
                data.readBytes(inBody, data.readableBytes());
            }
            if (content instanceof LastHttpContent) {
                SpooledBuffer reqBody = inBody;
                inBody = null;
                String target = pendingEndpoint;
                pendingEndpoint = null;
                HttpRequest req = request;
                File dir = repoDir;
                String user = authnUser;
                String authz = authnAuthz;
                request = null;
                repoDir = null;
                authnUser = null;
                authnAuthz = null;
                Log.logger.debug("received POST body {} bytes for {}", reqBody.size(), target);
                GitResponse resp;
                try (reqBody) {
                    boolean v2 = req != null && wantsV2(req);
                    ShallowRequest shallow = FetchRequestParser.parse(reqBody.input(), v2);
                    BufferedInputStream bin = new BufferedInputStream(reqBody.input());
                    if ("git-upload-pack".equals(target)) {
                        resp = new UploadPackService(dir, config)
                                .upload(bin, v2, shallow, user, authz);
                    } else if ("git-receive-pack".equals(target)) {
                        resp = new ReceivePackService(dir, config)
                                .receive(bin, user, authz);
                    } else {
                        resp = GitResponse.error(BAD_REQUEST, "unexpected request");
                    }
                } catch (Exception e) {
                    Log.logger.error("failed to process {} : {}", target, e.toString(), e);
                    resp = GitResponse.error(INTERNAL_SERVER_ERROR, e.toString());
                }
                writeResponse(ctx, resp);
            }
            return;
        }
        if (content instanceof LastHttpContent) {
            request = null;
            path = null;
            requestUrl = null;
            repoDir = null;
        }
    }

    private boolean wantsV2(HttpRequest req) {
        if (req == null) {
            return false;
        }
        String header = req.headers().get("Git-Protocol");
        if (header == null) {
            return false;
        }
        String cleaned = header.replace("\"", "");
        for (String part : cleaned.split("[\\s,]+")) {
            if ("version=2".equals(part)) {
                return true;
            }
        }
        return false;
    }

    private String queryParameter(HttpRequest req, String name) {
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        List<String> values = decoder.parameters().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private boolean hasQueryParameter(HttpRequest req, String name) {
        return new QueryStringDecoder(req.uri()).parameters().containsKey(name);
    }

    /**
     * Extracts the username from an {@code Authorization: Basic base64(user:token)}
     * header. Returns null when the header is absent or malformed (anonymous).
     * The raw header value itself is kept separately and forwarded to origin
     * verbatim, so the token is never decoded or stored here.
     */
    private static String parseBasicUser(String authz) {
        if (authz == null) {
            return null;
        }
        if (!authz.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String b64 = authz.substring(6).trim();
        if (b64.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(b64),
                    java.nio.charset.StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String user = decoded.substring(0, colon);
            return user.isEmpty() ? null : user;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns the part after the repo segment, e.g. "/x.git/info/refs" -> "info/refs". */
    private static String endpoint(String path) {
        if (path == null) {
            return null;
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        int slash = path.indexOf('/', start);
        if (slash < 0) {
            return null;
        }
        String ep = path.substring(slash + 1);
        return ep.isEmpty() ? null : ep;
    }

    private void writeResponse(ChannelHandlerContext ctx, GitResponse resp) {
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, resp.status);
        if (resp.contentType != null) {
            head.headers().set(CONTENT_TYPE, resp.contentType);
        }
        if (resp.wwwAuthenticate != null) {
            head.headers().set("WWW-Authenticate", resp.wwwAuthenticate);
        }
        head.headers().set(CACHE_CONTROL, "no-cache");
        long length = resp.body == null ? 0L : resp.body.size();
        head.headers().set(CONTENT_LENGTH, length);
        if (!keepAlive) {
            head.headers().set(CONNECTION, CLOSE);
        }
        Log.logger.debug("response {} {} for {} bytes={} keepAlive={}",
                resp.status.code(), resp.status.reasonPhrase(), requestUrl, length, keepAlive);
        for (Map.Entry<String, String> header : head.headers().entries()) {
            Log.logger.debug("  response header {}={}", header.getKey(), header.getValue());
        }
        ctx.write(head);

        if (resp.body != null && !bodyless) {
            try (InputStream in = resp.body.input()) {
                byte[] tmp = new byte[65536];
                int read;
                while ((read = in.read(tmp)) > 0) {
                    ByteBuf chunk = ctx.alloc().buffer(read);
                    chunk.writeBytes(tmp, 0, read);
                    ctx.write(chunk);
                }
            } catch (IOException e) {
                closeQuietly(resp.body);
                ctx.close();
                return;
            } finally {
                closeQuietly(resp.body);
            }
        } else if (resp.body != null) {
            closeQuietly(resp.body);
        }

        ChannelFuture future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void closeQuietly(SpooledBuffer body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.logger.error("http handler error for {} : {}", ctx.channel().remoteAddress(),
                cause.toString(), cause);
        ctx.close();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (inBody != null) {
            try {
                inBody.close();
            } catch (IOException ignored) {
                // no-op
            }
            inBody = null;
        }
    }
}