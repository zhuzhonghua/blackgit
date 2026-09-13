package com.blackhttp.blackgit;

import com.black.Log;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public final class BlackGitHandler extends SimpleChannelInboundHandler<BlackGitRequest> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BlackGitRequest req) {
        Log.net.debug("blackgit request from {} protocol={} bodyLength={}",
                ctx.channel().remoteAddress(), req.protocolName(), req.body().readableBytes());
        BlackGitProtocol protocol = BlackGitRegistry.get(req.protocolName());
        if (protocol == null) {
            Log.net.warn("unknown blackgit protocol {} from {}",
                    req.protocolName(), ctx.channel().remoteAddress());
            BlackGitReply.send(ctx, "unknown protocol: " + req.protocolName());
            return;
        }
        try {
            protocol.handle(ctx, req.body());
        } catch (Exception e) {
            Log.net.error("blackgit protocol {} error : {}", req.protocolName(), e.toString(), e);
            BlackGitReply.send(ctx, "protocol error: " + e.getMessage());
        } finally {
            req.body().release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.net.error("blackgit connection error {} : {}",
                ctx.channel().remoteAddress(), cause.toString(), cause);
        ctx.close();
    }
}