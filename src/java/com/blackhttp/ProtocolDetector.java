package com.blackhttp;

import com.black.Log;
import com.blackhttp.githttp.GitHttpHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import java.util.List;
import java.util.Set;

/**
 * Decides from the first bytes of a connection whether the client is speaking
 * git's smart HTTP protocol or a TLS handshake (when --tls is enabled), then
 * installs the matching HTTP pipeline and removes itself. Any other framing
 * (including the legacy custom BlackGit wire protocol) is rejected by closing
 * the connection — only standard HTTP(S) git access is supported. The legacy
 * wire-protocol handlers ({@code BlackGitDecoder}/{@code BlackGitHandler}) are
 * kept in the source tree but intentionally no longer routed to.
 *
 * <p>The buffered leading bytes are handed to the new pipeline by
 * ByteToMessageDecoder.handlerRemoved(), which fires the remaining cumulation
 * downstream exactly once when a decoder removes itself mid-decode.
 */
final class ProtocolDetector extends ByteToMessageDecoder {
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");
    private enum Route { HTTP, TLS_HTTP, REJECT }

    private final Server server;

    ProtocolDetector(Server server) {
        this.server = server;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // ByteToMessageDecoder buffers for us; with at least the 4 leading bytes
        // (TLS record header or an HTTP method token) decide() can always
        // produce a Route.
        if (in.readableBytes() < 4) {
            return;
        }

        Route route = decide(in);
                Log.net.debug("{} from {} -> {} pipeline",
                        ctx.channel().remoteAddress(), route, ctx.channel().localAddress());

        if (route == Route.REJECT) {
            // Non-HTTP traffic (e.g. a legacy BlackGit wire frame) is not
            // supported: log and drop the connection rather than installing the
            // old wire-protocol pipeline.
            Log.net.warn("non-HTTP protocol from {} rejected (only HTTP(S) git is supported), closing",
                    ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

                install(ctx, route);

        // Removing ourselves mid-decode must not deliver the cumulation twice:
        // Netty defers handlerRemoved() until decode returns (decodeRemovalReentryProtection),
        // and that deferred handlerRemoved() hands the readable cumulation to the
        // newly installed pipeline. Adding in.retain() to `out` would fire it TWICE.
        ctx.pipeline().remove(this);
    }

    private Route decide(ByteBuf in) {
        int base = in.readerIndex();
        if (server.sslEnabled()
                && in.getUnsignedByte(base) == 0x16
                && in.getUnsignedByte(base + 1) == 0x03) {
            return Route.TLS_HTTP;
        }
        return isHttpMethodPrefix(in) ? Route.HTTP : Route.REJECT;
        }

    /**
     * HTTP request lines always begin with a fixed method token. The first 4
     * bytes are therefore "GET ", "POST", "HEAD", "PUT ", or the first 4
     * letters of a longer method. Anything else is treated as unsupported and
     * rejected (the old wire protocol's length-prefixed frames fall into this
     * bucket).
     */
    private static boolean isHttpMethodPrefix(ByteBuf in) {
        // Set.of("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT")
        int base = in.readerIndex();
        byte b0 = in.getByte(base);
        byte b1 = in.getByte(base + 1);
        byte b2 = in.getByte(base + 2);
        byte b3 = in.getByte(base + 3);
        return (b0 == 'G' && b1 == 'E' && b2 == 'T' && b3 == ' ')
                || (b0 == 'P' && b1 == 'U' && b2 == 'T' && b3 == ' ')
                || (b0 == 'P' && b1 == 'O' && b2 == 'S' && b3 == 'T')
                || (b0 == 'H' && b1 == 'E' && b2 == 'A' && b3 == 'D')
                || (b0 == 'D' && b1 == 'E' && b2 == 'L' && b3 == 'E')
                || (b0 == 'P' && b1 == 'A' && b2 == 'T' && b3 == 'C')
                || (b0 == 'O' && b1 == 'P' && b2 == 'T' && b3 == 'I')
                || (b0 == 'T' && b1 == 'R' && b2 == 'A' && b3 == 'C')
                || (b0 == 'C' && b1 == 'O' && b2 == 'N' && b3 == 'N');
    }

    private void install(ChannelHandlerContext ctx, Route route) {
        ChannelPipeline pipeline = ctx.pipeline();
        switch (route) {
            case TLS_HTTP: {
                SslContext ssl = server.sslContext();
                pipeline.addLast(new SslHandler(ssl.newEngine(ctx.alloc())));
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpServerExpectContinueHandler());
                pipeline.addLast(server.httpExecutor(),
                        new GitHttpHandler(server.repoResolver(), server.config()));
                break;
            }
            case HTTP: {
                pipeline.addLast(new HttpServerCodec());
                pipeline.addLast(new HttpServerExpectContinueHandler());
                pipeline.addLast(server.httpExecutor(),
                        new GitHttpHandler(server.repoResolver(), server.config()));
                break;
            }
            case REJECT:
            default: {
                // REJECT is handled in decode() before install() is reached.
                break;
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.net.error("error on connection {} : {}",
                ctx.channel().remoteAddress(), cause.toString(), cause);
        ctx.close();
    }
}