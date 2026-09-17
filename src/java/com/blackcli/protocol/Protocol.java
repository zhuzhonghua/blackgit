package com.blackcli.protocol;

import com.blackcli.SocketClient;

import java.nio.ByteBuffer;

public interface Protocol {
    String name();

    void handle(SocketClient client, ByteBuffer data) throws Exception;
}