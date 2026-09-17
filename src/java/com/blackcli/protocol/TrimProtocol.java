package com.blackcli.protocol;

import com.black.BlackGit;
import com.black.Log;
import com.black.TrimService;
import com.blackcli.SocketClient;
import com.blackcli.Util;
import com.black.trim.VirtualCommit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class TrimProtocol implements Protocol {

    @Override
    public String name() {
        return "trim";
    }

    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        String text = Util.tostr(data);
        Log.logger.debug("trim request from {}", client.addr);
        try {
            Req req = Req.parse(text);
            if (req == null) {
                client.write(("ERROR: bad trim request, want: sha=<sha>\npath=<path>\nbody:\n" + text)
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }

            TrimService.Result result = TrimService.trim(BlackGit.bg.repository, req.sha, req.path);
            Log.logger.debug("trim {}:{}/{} -> {} ({} byte pack) to {}",
                    req.sha, req.path, result.virtualSha, result.pack.length, client.addr);

            ByteArrayOutputStream resp = new ByteArrayOutputStream(result.pack.length + 48);
            resp.write((result.virtualSha + "\n").getBytes(StandardCharsets.UTF_8));
            resp.write(result.pack);
            client.write(resp.toByteArray());
        } catch (Exception e) {
            Log.logger.warn("trim failed for {} err={}", text.replace("\n", "|"), e.toString());
            client.write(("ERROR: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Req {
        String sha = null;
        String path = VirtualCommit.ROOT_PATH;

        static Req parse(String text) {
            Req r = new Req();
            for (String raw : text.split("\n")) {
                String l = raw.trim();
                if (l.isEmpty()) {
                    continue;
                }
                if (l.startsWith("sha=") || l.startsWith("path=")) {
                    int eq = l.indexOf('=');
                    String key = l.substring(0, eq);
                    String val = l.substring(eq + 1).trim();
                    if (key.equals("sha")) {
                        if (r.sha != null) {
                            return null;
                        }
                        r.sha = val;
                    } else {
                        r.path = val;
                    }
                } else if (r.sha == null && !l.startsWith("recursive=")) {
                    // tolerate a bare positional sha
                    r.sha = l;
                }
                // unknown keys (e.g. recursive=0/1) are ignored for now
            }
            if (r.sha == null || !r.sha.matches("[0-9a-fA-F]{40}")) {
                return null;
            }
            return r;
        }
    }
}