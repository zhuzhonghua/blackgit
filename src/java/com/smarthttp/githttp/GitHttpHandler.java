package com.smarthttp.githttp;

import com.blackgit.Log;
import com.smarthttp.Config;
import com.smarthttp.SpooledBuffer;
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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

public final class GitHttpHandler extends ChannelInboundHandlerAdapter {

    private final RepoResolver resolver;
    private final Config config;

    private HttpRequest request;
    private String method;
    private String path;
    private boolean keepAlive;
    private boolean bodyless;
    private File repoDir;
    private String pendingEndpoint;
    private SpooledBuffer inBody;

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
        Log.logger.debug("HTTP {} {} from {} keepAlive={} query={}",
                method, path, ctx.channel().remoteAddress(), keepAlive, query);

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

        if (HttpMethod.GET.name().equals(method) || HttpMethod.HEAD.name().equals(method)) {
            if ("info/refs".equals(endpoint)) {
                bodyless = HttpMethod.HEAD.name().equals(method);
                String service = queryParameter(req, "service");
                boolean v2 = wantsV2(req);
                Log.logger.debug("info/refs request service={} protocolV2={}", service, v2);
                writeResponse(ctx, new InfoRefsService(dir, config).advertise(service, v2));
                return;
            }
            writeResponse(ctx, GitResponse.error(NOT_FOUND, "not found"));
            return;
        }
        if (HttpMethod.POST.name().equals(method)
                && ("git-upload-pack".equals(endpoint) || "git-receive-pack".equals(endpoint))) {
            inBody = new SpooledBuffer(config.spoolMemoryLimit);
            pendingEndpoint = endpoint;
            return;
        }
        writeResponse(ctx, GitResponse.error(BAD_REQUEST, "unexpected method or path"));
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
                request = null;
                repoDir = null;
                Log.logger.debug("received POST body {} bytes for {}", reqBody.size(), target);
                GitResponse resp;
                try (reqBody) {
                    boolean v2 = req != null && wantsV2(req);
                    BufferedInputStream bin = new BufferedInputStream(reqBody.input());
                    if ("git-upload-pack".equals(target)) {
                        resp = new UploadPackService(dir, config).upload(bin, v2);
                    } else if ("git-receive-pack".equals(target)) {
                        resp = new ReceivePackService(dir, config).receive(bin);
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
        head.headers().set(CACHE_CONTROL, "no-cache");
        long length = resp.body == null ? 0L : resp.body.size();
        head.headers().set(CONTENT_LENGTH, length);
        if (!keepAlive) {
            head.headers().set(CONNECTION, CLOSE);
        }
        Log.logger.debug("response {} {} path={} bytes={} keepAlive={}",
                resp.status.code(), resp.status.reasonPhrase(), path, length, keepAlive);
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