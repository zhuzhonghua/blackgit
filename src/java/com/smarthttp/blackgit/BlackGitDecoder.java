package com.smarthttp.blackgit;

import com.blackgit.Log;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes the BlackGit wire format into {@link BlackGitRequest}s.
 *
 * <pre>
 *   request:  <4-byte BE frame length> <protocol name> '\n' <body>
 *   frame length = number of bytes after the length field, i.e.
 *                 len(name) + 1 (for '\n') + len(body)
 *   reply:    <4-byte BE body length> <body>
 * </pre>
 */
public final class BlackGitDecoder extends ByteToMessageDecoder {
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_BODY_LENGTH = 64 * 1024 * 1024;
    private static final int MAX_FRAME_LENGTH = MAX_NAME_LENGTH + 1 + MAX_BODY_LENGTH;

    private enum State { LENGTH, NAME, BODY }

    private State state = State.LENGTH;
    private String protocolName;
    private int lengthLeft;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            switch (state) {
                case LENGTH: {
                    if (in.readableBytes() < 4) {
                        return;
                    }
                    int frame = in.readInt();
                    if (frame < 2 || frame > MAX_FRAME_LENGTH) {
                        throw new IllegalArgumentException("bad frame length " + frame);
                    }
                    lengthLeft = frame;
                    state = State.NAME;
                    break;
                }
                case NAME: {
                    int start = in.readerIndex();
                    int end = Math.min(in.writerIndex(), start + lengthLeft);
                    int nl = in.indexOf(start, end, (byte) '\n');
                    if (nl >= 0) {
                        int nameLen = nl - start;
                        if (nameLen <= 0 || nameLen > MAX_NAME_LENGTH) {
                            throw new IllegalArgumentException("bad protocol name length " + nameLen);
                        }
                        ByteBuf nameBytes = in.readRetainedSlice(nameLen);
                        protocolName = nameBytes.toString(StandardCharsets.US_ASCII).trim();
                        nameBytes.release();
                        in.readByte();
                        lengthLeft -= nameLen + 1;
                        if (lengthLeft > MAX_BODY_LENGTH) {
                            throw new IllegalArgumentException("body too large " + lengthLeft);
                        }
                        state = State.BODY;
                    } else {
                        if (end - start > MAX_NAME_LENGTH) {
                            throw new IllegalArgumentException("protocol name too long");
                        }
                        if (end >= in.writerIndex()) {
                            throw new IllegalArgumentException("missing newline before frame end");
                        }
                        return;
                    }
                    break;
                }
                case BODY: {
                    if (in.readableBytes() < lengthLeft) {
                        return;
                    }
                    ByteBuf body = in.readRetainedSlice(lengthLeft);
                    Log.net.debug("blackgit frame protocol={} bodyLength={}", protocolName, lengthLeft);
                    out.add(new BlackGitRequest(protocolName, body));
                    reset();
                    break;
                }
                default:
                    throw new IllegalStateException("unknown state " + state);
            }
        }
    }

    private void reset() {
        state = State.LENGTH;
        protocolName = null;
        lengthLeft = 0;
    }
}