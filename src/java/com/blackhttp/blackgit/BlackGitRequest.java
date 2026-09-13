package com.blackhttp.blackgit;

import io.netty.buffer.ByteBuf;

public final class BlackGitRequest {
    private final String protocolName;
    private final ByteBuf body;

    public BlackGitRequest(String protocolName, ByteBuf body) {
        this.protocolName = protocolName;
        this.body = body;
    }

    public String protocolName() {
        return protocolName;
    }

    public ByteBuf body() {
        return body;
    }
}