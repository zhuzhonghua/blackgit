package com.blackgit.protocol;

import com.blackgit.*;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class FetchProtocol implements Protocol {
    public static final int MAX_COMMITS = 100;

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        String text = Util.tostr(data);
        Log.logger.debug("fetch request from {} shas=[{}]", client.addr, text.trim().replace("\n", ","));
        byte[] pack = getFetch(BlackGit.bg.repository, text.split("\n"));
        Log.logger.debug("fetch reply {} bytes to {}", pack.length, client.addr);
        client.write(pack);
    }

    public byte[] getFetch(Repository repository, String[] shas) throws IOException {
        backfillMissing(repository, shas);

        Set<RevObject> objects = new HashSet<>();

        Set<String> already = new HashSet<>();
        for (String sha : shas) {
            if (already.contains(sha))
                continue;
            already.add(sha);
            ObjectId id = ObjectId.fromString(sha.trim());

            try (RevWalk rw = new RevWalk(repository)) {
                RevObject obj = rw.parseAny(id);

                if (obj instanceof RevCommit) {
                    rw.markStart((RevCommit) obj);
                    int n = 0;
                    for (RevCommit c : rw) {
                        objects.add(c);
                        if (++n >= MAX_COMMITS) {
                            break;
                        }
                    }
                } else if (obj instanceof RevTree) {
                    collectTree(repository, rw, id, 0, 1, objects);
                } else if (obj instanceof RevBlob) {
                    objects.add(obj);
                }
            }
        }

        Log.logger.debug("fetch pack {} objects for {} shas", objects.size(), shas.length);
        PackWriter pw = new PackWriter(repository);
        pw.preparePack(objects.iterator());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);

        return baos.toByteArray();
    }

    private static void backfillMissing(Repository repository, String[] shas) throws IOException {
        Set<ObjectId> missing = new HashSet<>();
        for (String sha : shas) {
            String t = sha == null ? "" : sha.trim();
            if (t.isEmpty()) {
                continue;
            }
            ObjectId id;
            try {
                id = ObjectId.fromString(t);
            } catch (IllegalArgumentException e) {
                continue;
            }
            try (RevWalk rw = new RevWalk(repository)) {
                rw.parseAny(id);
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty() && OriginBackfill.hasOrigin(repository)) {
            Log.logger.info("fetch backfill {} missing objects from origin", missing.size());
            try {
                OriginBackfill.fetchFromOrigin(repository);
            } catch (Exception e) {
                Log.logger.warn("fetch backfill from origin failed: {}", e.toString());
            }
        }
    }

    private void collectTree(Repository repository, RevWalk rw, ObjectId treeId,
                             int depth, int maxDepth,
                             Set<RevObject> objects) throws IOException {
        objects.add(rw.parseTree(treeId));

        if (depth >= maxDepth) return;

        try (TreeWalk tw = new TreeWalk(repository)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (FileMode.TREE.equals(tw.getFileMode(0))) {
                    collectTree(repository, rw, tw.getObjectId(0), depth + 1, maxDepth, objects);
                }
                // skip blob
            }
        }
    }
}