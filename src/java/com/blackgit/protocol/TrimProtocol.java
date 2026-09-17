package com.blackgit.protocol;

import com.black.Log;
import com.blackgit.BlackGit;
import com.blackgit.OriginBackfill;
import com.blackgit.SocketClient;
import com.blackgit.Util;
import com.blackgit.trim.VirtualCommit;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * "trim" protocol — josh-style subtree trim served over the blackgit wire.
 *
 * Request body (one line each, missing keys use defaults):
 *   sha=<commit sha>
 *   path=<subtree path or ".">
 *
 * (unknown keys are ignored for forward-compatibility, e.g. a future
 * recursive=0/1 param).
 *
 * Reply: one frame whose first line is the virtual commit sha, followed by a
 * pack with exactly two objects: the virtual commit and the trimmed root
 * tree. No blobs, no sub-trees — the client can also ask the server for the
 * rest later (recursion to be controlled by a parameter).
 *
 * The virtual commit is written into the server odb (and is deterministic),
 * so a later request with the same (sha, path) reuses the same object.
 */
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

            VirtualCommit.Result result = trimWithBackfill(req.sha, req.path);
            byte[] pack = buildPack(result);
            Log.logger.debug("trim {}:{}/{} -> {} ({} byte pack) to {}",
                    req.sha, req.path, result.commit.name(), pack.length, client.addr);

            ByteArrayOutputStream resp = new ByteArrayOutputStream(pack.length + 48);
            resp.write((result.commit.name() + "\n").getBytes(StandardCharsets.UTF_8));
            resp.write(pack);
            client.write(resp.toByteArray());
        } catch (Exception e) {
            Log.logger.warn("trim failed for {} err={}", text.replace("\n", "|"), e.toString());
            client.write(("ERROR: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private VirtualCommit.Result trimWithBackfill(String sha, String path) throws IOException {
        ObjectId id = ObjectId.fromString(sha.trim());
        boolean backfilled = false;
        while (true) {
            try {
                return VirtualCommit.get(BlackGit.bg.repository, id, path);
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                // Lazy backfill: only hit origin when the object is not present
                // locally, then retry once (same pattern as fetch).
                if (!backfilled && OriginBackfill.hasOrigin(BlackGit.bg.repository)) {
                    Log.logger.info("trim backfill from origin for missing {}", id.name());
                    try {
                        OriginBackfill.fetchFromOrigin(BlackGit.bg.repository);
                    } catch (Exception ex) {
                        Log.logger.warn("trim backfill from origin failed: {}", ex.toString());
                    }
                    backfilled = true;
                } else {
                    throw new IOException("trim " + id.name() + " path=" + path + ": "
                            + e.getMessage(), e);
                }
            }
        }
    }

    private byte[] buildPack(VirtualCommit.Result result) throws IOException {
        Repository repo = BlackGit.bg.repository;
        Set<RevObject> objects = new HashSet<>();
        try (RevWalk rw = new RevWalk(repo)) {
            objects.add(rw.parseCommit(result.commit));
            objects.add(rw.parseTree(result.tree));
        }
        PackWriter pw = new PackWriter(repo);
        pw.preparePack(objects.iterator());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);
        return baos.toByteArray();
    }

    /**
     * Loose key=value request parser. Also tolerant of a bare first-line sha
     * (path defaults to ".").
     */
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
            if (r.sha == null) {
                return null;
            }
            try {
                ObjectId.fromString(r.sha);
            } catch (Exception e) {
                return null;
            }
            return r;
        }
    }
}