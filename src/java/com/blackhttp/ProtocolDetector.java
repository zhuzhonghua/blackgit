package com.blackhttp;

import com.black.Log;
import com.blackhttp.blackgit.BlackGitDecoder;
import com.blackhttp.blackgit.BlackGitHandler;
import com.blackhttp.githttp.GitHttpHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;

import java.util.Set;

/**
 * Decides from the first bytes of a connection whether the client is speaking
 * git's smart HTTP protocol, a TLS handshake (when --tls is enabled), or the
 * custom BlackGit wire protocol, then installs the matching pipeline.
 */
final class ProtocolDetector extends ChannelInboundHandlerAdapter {
    
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");

    private enum Route { HTTP, TLS_HTTP, BLACKGIT }

    private final Server server;
    private ByteBuf head;
    private boolean decided;

    ProtocolDetector(Server server) {
        this.server = server;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Log.net.debug("connection established from {}", ctx.channel().remoteAddress());
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        if (!decided) {
            if (head == null) {
                head = ctx.alloc().buffer(Math.max(256, in.readableBytes()));
            }
            head.writeBytes(in);
            in.release();

            // With at least the 4 leading bytes (TLS record header, HTTP method
            // token, or the frame length of the wire protocol) decide() can
            // always produce a Route, so don't proceed until we have them.
            if (head.readableBytes() >= 4) {
                decided = true;
                Route route = decide();
                Log.net.debug("{} from {} -> {} pipeline",
                        ctx.channel().remoteAddress(), route, ctx.channel().localAddress());
                install(ctx, route);
                if (head.isReadable()) {
                    ctx.fireChannelRead(head);
                } else {
                    head.release();
                }
                head = null;
            }
        } else {
            ctx.fireChannelRead(in);
        }
    }

    private Route decide() {
        int base = head.readerIndex();
        if (server.sslEnabled()
                && head.getUnsignedByte(base) == 0x16
                && head.getUnsignedByte(base + 1) == 0x03) {
            return Route.TLS_HTTP;
        }
        return isHttpMethodPrefix(head) ? Route.HTTP : Route.BLACKGIT;
        }

    /**
     * HTTP request lines always begin with a fixed method token. The first 4
     * bytes are therefore "GET ", "POST", "HEAD", "PUT ", or the first 4
     * letters of a longer method. A BlackGit frame starts with a big-endian
     * length which falls far below the byte values of these tokens, so the two
     * protocols can never collide on the first 4 bytes.
     */
    private static boolean isHttpMethodPrefix(ByteBuf head) {
        int base = head.readerIndex();
        byte b0 = head.getByte(base);
        byte b1 = head.getByte(base + 1);
        byte b2 = head.getByte(base + 2);
        byte b3 = head.getByte(base + 3);
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
            case BLACKGIT:
            default: {
                pipeline.addLast(new BlackGitDecoder());
                pipeline.addLast(new BlackGitHandler());
                break;
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Log.net.debug("connection closed from {}", ctx.channel().remoteAddress());
        releaseHead();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.net.error("error on connection {} : {}",
                ctx.channel().remoteAddress(), cause.toString(), cause);
        releaseHead();
        ctx.close();
    }

    private void releaseHead() {
        if (head != null) {
            head.release();
            head = null;
        }
    }
}