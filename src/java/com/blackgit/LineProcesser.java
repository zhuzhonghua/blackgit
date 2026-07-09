package com.blackgit;

import java.util.Map;
import java.util.stream.Collectors;

public class LineProcesser {
    private SocketClient client;

    public LineProcesser(SocketClient s) {
        client = s;
    }

    public void processLine(String line) throws Exception {
        String[] args = line.split("\\s+");
        if (args[0].equals("list")) {
            Map<String, String> nameId = BlackGit.bg.getList();
            String data = nameId.entrySet().stream()
                    .map(entry -> entry.getValue() + ' ' + entry.getKey())
                    .collect(Collectors.joining("\n"));
            client.write(data);
            Log.logger.info("write back {}", data);
        }
    }
}
