package com.blackcli.protocol;

import com.black.BlackGit;
import com.black.Log;
import com.black.UnapplyService;
import com.blackcli.SocketClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UnapplyProtocol implements Protocol {

    @Override
    public String name() {
        return "unapply";
    }

    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        Log.logger.debug("unapply request from {}", client.addr);
        byte[] all = toBytes(data);
        Req req = null;
        try {
            int split = indexOfDoubleNewline(all);
            if (split < 0) {
                throw new IOException("malformed unapply request: no blank line before pack");
            }
            req = Req.parse(headerText(all, split));
            if (req == null) {
                throw new IOException("bad unapply request, want: from=<sha>\nto=<sha>\n[branch=<ref>]");
            }
            byte[] pack = Arrays.copyOfRange(all, split + 2, all.length);

            if (pack.length > 0) {
                UnapplyService.importPack(BlackGit.bg.repository, pack);
            }

            UnapplyService.Result result =
                    UnapplyService.unapply(BlackGit.bg.repository, req.from, req.to, req.branch);
            Log.logger.debug("unapply {}..{} -> {} ({} real commit(s) ref={}) from {}",
                    req.from, req.to, result.commitSha, result.count, req.branch, client.addr);

            client.write((result.commitSha + "\ncount=" + result.count + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String from = req != null ? req.from : "?";
            String to = req != null ? req.to : "?";
            Log.logger.warn("unapply failed for {}..{} err={}", from, to, e.toString());
            client.write(("ERROR: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] toBytes(ByteBuffer data) {
        byte[] arr = new byte[data.remaining()];
        data.get(arr);
        return arr;
    }

    private static int indexOfDoubleNewline(byte[] all) {
        for (int i = 0; i + 1 < all.length; i++) {
            if (all[i] == '\n' && all[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String headerText(byte[] all, int split) {
        return new String(all, 0, split, StandardCharsets.UTF_8);
    }

    private static final class Req {
        String from;
        String to;
        String branch;

        static Req parse(String text) {
            Req r = new Req();
            for (String raw : text.split("\n")) {
                String l = raw.trim();
                if (l.isEmpty()) {
                    continue;
                }
                int eq = l.indexOf('=');
                if (eq <= 0) {
                    return null;
                }
                String key = l.substring(0, eq);
                String val = l.substring(eq + 1).trim();
                    switch (key) {
                        case "from":
                            if (r.from != null) {
                                return null;
                            }
                        r.from = val;
                            break;
                        case "to":
                            if (r.to != null) {
                                return null;
                            }
                        r.to = val;
                            break;
                        case "branch":
                            r.branch = val;
                            break;
                        default:
                            // future keys ignored
                            break;
                    }
            }
            if (r.from == null || r.to == null || !isSha(r.from) || !isSha(r.to)) {
                return null;
            }
            return r;
        }

        private static boolean isSha(String s) {
            return s != null && s.matches("[0-9a-fA-F]{40}");
        }
    }
}