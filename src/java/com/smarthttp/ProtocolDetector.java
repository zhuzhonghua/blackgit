package com.smarthttp;

import com.blackgit.Log;
import com.smarthttp.blackgit.BlackGitDecoder;
import com.smarthttp.blackgit.BlackGitHandler;
import com.smarthttp.githttp.GitHttpHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Decides from the first bytes of a connection whether the client is speaking
 * git's smart HTTP protocol, a TLS handshake (when --tls is enabled), or the
 * custom BlackGit wire protocol, then installs the matching pipeline.
 */
final class ProtocolDetector extends ChannelInboundHandlerAdapter {
    private static final int MAX_HEAD = 64;
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
        Log.logger.info("connection established from {}", ctx.channel().remoteAddress());
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

            Route route = decide();
            if (route != null) {
                decided = true;
                Log.logger.debug("{} from {} -> {} pipeline",
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
        int readable = head.readableBytes();
        if (readable >= 2 && server.sslEnabled()
                && head.getUnsignedByte(head.readerIndex()) == 0x16
                && head.getUnsignedByte(head.readerIndex() + 1) == 0x03) {
            return Route.TLS_HTTP;
        }
        int base = head.readerIndex();
        int scan = Math.min(readable, MAX_HEAD);
        int newline = -1;
        int space = -1;
        for (int i = 0; i < scan; i++) {
            byte b = head.getByte(base + i);
            if (b == '\n') {
                newline = i;
                break;
            }
            if (b == ' ') {
                space = i;
                break;
            }
        }
        if (newline >= 0) {
            return Route.BLACKGIT;
        }
        if (space >= 0) {
            String token = head.toString(base, space, StandardCharsets.US_ASCII);
            return HTTP_METHODS.contains(token) ? Route.HTTP : Route.BLACKGIT;
        }
        return readable >= MAX_HEAD ? Route.BLACKGIT : null;
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
        Log.logger.debug("connection closed from {}", ctx.channel().remoteAddress());
        releaseHead();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.logger.error("error on connection {} : {}",
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