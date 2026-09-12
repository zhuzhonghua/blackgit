package com.blackgit.protocol;

import com.blackgit.SocketClient;

import java.nio.ByteBuffer;

public interface Protocol {
    String name();

    void handle(SocketClient client, ByteBuffer data) throws Exception;
}