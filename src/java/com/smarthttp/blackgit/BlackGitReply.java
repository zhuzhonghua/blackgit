package com.smarthttp.blackgit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;

public final class BlackGitReply {
    private BlackGitReply() {
    }

    public static void send(ChannelHandlerContext ctx, byte[] data) {
        ByteBuf buf = ctx.alloc().buffer(4 + data.length);
        buf.writeInt(data.length);
        buf.writeBytes(data);
        ctx.writeAndFlush(buf);
    }

    public static void send(ChannelHandlerContext ctx, String text) {
        send(ctx, text.getBytes(StandardCharsets.UTF_8));
    }
}