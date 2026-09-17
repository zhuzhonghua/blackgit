package com.blackgit.protocol;

import com.black.Log;
import com.blackgit.BlackGit;
import com.blackgit.SocketClient;
import com.blackgit.trim.VirtualCommitUnapply;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PackParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * "unapply" protocol — the server-side reverse of a virtual-commit push.
 *
 * A client that built new commits on top of a virtual (trimmed) commit sends
 * them back here; the server expands them into the *real* history and
 * advances the real branch.
 *
 * Request body: header lines (key=value), terminated by an empty line,
 * followed by the raw pack bytes carrying the new virtual objects:
 *
 *   from=<virtual base sha>
 *   to=<virtual tip sha>
 *   branch=<real branch, refs/heads/... or short>   (optional)
 *
 * Reply: one frame
 *   <new real tip sha>\n
 *   count=<number of real commits created>\n
 * or "ERROR: <reason>".
 *
 * Note: the request body is currently capped by the socket layer, so packs
 * must fit within MAX_BUFFER_SIZE. Fine for now; revisit when real pushes land.
 */
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
                importPack(pack);
            }

            VirtualCommitUnapply.Result result =
                    VirtualCommitUnapply.unapply(BlackGit.bg.repository, req.from, req.to, req.branch);
            Log.logger.debug("unapply {}..{} -> {} ({} real commit(s) ref={}) from {}",
                    req.from.name(), req.to.name(), result.commit.name(), result.count,
                    req.branch, client.addr);

            client.write((result.commit.name() + "\ncount=" + result.count + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            String from = req != null ? req.from.name() : "?";
            String to = req != null ? req.to.name() : "?";
            Log.logger.warn("unapply failed for {}..{} err={}", from, to, e.toString());
            client.write(("ERROR: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void importPack(byte[] pack) throws IOException {
        Repository repo = BlackGit.bg.repository;
        try (ObjectInserter ins = repo.newObjectInserter();
             InputStream in = new ByteArrayInputStream(pack)) {
            PackParser parser = ins.newPackParser(in);
            parser.setAllowThin(false);
            parser.setLockMessage("unapply push");
            parser.parse(NullProgressMonitor.INSTANCE);
            ins.flush();
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
        ObjectId from;
        ObjectId to;
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
                try {
                    switch (key) {
                        case "from":
                            if (r.from != null) {
                                return null;
                            }
                            r.from = ObjectId.fromString(val);
                            break;
                        case "to":
                            if (r.to != null) {
                                return null;
                            }
                            r.to = ObjectId.fromString(val);
                            break;
                        case "branch":
                            r.branch = val;
                            break;
                        default:
                            // future keys ignored
                            break;
                    }
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
            if (r.from == null || r.to == null) {
                return null;
            }
            return r;
        }
    }
}