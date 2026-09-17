package com.blackcli.protocol;

import com.black.BlackGit;
import com.black.FetchService;
import com.black.Log;
import com.blackcli.SocketClient;
import com.blackcli.Util;

import java.nio.ByteBuffer;

public class FetchProtocol implements Protocol {
    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        String text = Util.tostr(data);
        Log.logger.debug("fetch request from {} shas=[{}]", client.addr, text.trim().replace("\n", ","));
        byte[] pack = FetchService.fetch(BlackGit.bg.repository, text.split("\n"));
        Log.logger.debug("fetch reply {} bytes to {}", pack.length, client.addr);
        client.write(pack);
    }
}