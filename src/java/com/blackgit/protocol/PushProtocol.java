package com.blackgit.protocol;

import com.black.Log;
import com.blackgit.SocketClient;

import java.nio.ByteBuffer;

public class PushProtocol implements Protocol {
    @Override
    public String name() {
        return "push";
    }

    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        Log.logger.info("Push protocol not yet implemented");
        client.write("ERROR: Push not implemented\n");
    }
}