package com.blackcli.protocol;

import com.black.BlackGit;
import com.black.ListService;
import com.black.Log;
import com.blackcli.SocketClient;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

public class ListProtocol implements Protocol {
    @Override
    public String name() {
        return "list";
    }
    
    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        Log.logger.debug("list request from {}", client.addr);
        Map<String, String> nameId = new TreeMap<>(ListService.list(BlackGit.bg.repository));
        String headTarget = getHeadTarget(nameId);
        Log.logger.debug("list reply {} refs from repo {}, HEAD -> {}",
                nameId.size(), BlackGit.bg.repoPath, headTarget);
        for (Map.Entry<String, String> entry : nameId.entrySet()) {
            Log.logger.debug("list ref {} {}", entry.getValue(), entry.getKey());
        }
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(headTarget).append(' ').append("HEAD").append('\n');
        for (Map.Entry<String, String> entry : nameId.entrySet()) {
            sb.append(entry.getValue());
            sb.append(' ');
            sb.append(entry.getKey());
            sb.append('\n');
        }
        client.write(sb.toString());
    }

    public String getHeadTarget(Map<String, String> nameId) throws Exception {
        String full = BlackGit.bg.getHeadTarget();
        if (nameId.containsKey(full)) {
            return full;
        }
        throw new Exception("no head in "+full+" "+BlackGit.bg.repoPath);
    }
}