package com.blackhttp.blackgit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * A protocol handler for the BlackGit wire protocol.
 *
 * <pre>
 *   request:  <4-byte BE frame length> <protocol name> '\n' <body>
 *   frame length = len(name) + 1 (for '\n') + len(body)
 *   reply:    <4-byte BE body length> <body>
 * </pre>
 */
public interface BlackGitProtocol {

    String name();

    void handle(ChannelHandlerContext ctx, ByteBuf body) throws Exception;
}