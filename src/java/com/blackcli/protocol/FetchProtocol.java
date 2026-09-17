package com.blackcli.protocol;

import com.black.Log;
import com.blackcli.*;
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
import java.util.HashSet;
import java.util.Set;

public class FetchProtocol implements Protocol {
    public static final int MAX_COMMITS = 10;

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
        // NOTE: depth=/filter= header lines from the helper are
        // intentionally ignored: the server always returns at most
        // MAX_COMMITS consecutive commits. The client is shallow+sparse
        // by default (.git/shallow marks the boundary), trees are
        // included per commit, blobs only when a blob is explicitly
        // requested (promisor on-demand).
        java.util.List<String> shaList = new java.util.ArrayList<>();
        for (String line : shas) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith("depth=") || t.startsWith("filter=")) {
                continue;
            }
            shaList.add(t);
        }

        Set<RevObject> objects = new HashSet<>();

        Set<String> already = new HashSet<>();
        boolean backfilled = false;
        for (String sha : shaList) {
            if (already.contains(sha))
                continue;
            already.add(sha);
            ObjectId id = ObjectId.fromString(sha.trim());

            // Lazy backfill: only hit origin when the object is not
            // found locally (parse fails), then retry once.
            boolean done = false;
            while (!done) {
                try (RevWalk rw = new RevWalk(repository)) {
                    RevObject obj = rw.parseAny(id);

                    if (obj instanceof RevCommit) {
                        rw.markStart((RevCommit) obj);
                        int n = 0;
                        for (RevCommit c : rw) {
                            objects.add(c);
                            collectTreeRecursive(repository, rw, c.getTree().getId(), objects);
                            if (++n >= MAX_COMMITS) {
                                break;
                            }
                        }
                    } else if (obj instanceof RevTree) {
                        collectTreeRecursive(repository, rw, id, objects);
                    } else if (obj instanceof RevBlob) {
                        objects.add(obj);
                    }
                    done = true;
                } catch (MissingObjectException | IncorrectObjectTypeException e) {
                    if (!backfilled && OriginBackfill.hasOrigin(repository)) {
                        Log.logger.info("fetch backfill from origin for missing {}", id.name());
                        try {
                            OriginBackfill.fetchFromOrigin(repository);
                        } catch (Exception ex) {
                            Log.logger.warn("fetch backfill from origin failed: {}", ex.toString());
                        }
                        backfilled = true;
                    } else {
                        throw e;
                    }
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

    private void collectTreeRecursive(Repository repository, RevWalk rw, ObjectId treeId,
                                        Set<RevObject> objects) throws IOException {
        objects.add(rw.parseTree(treeId));
        try (TreeWalk tw = new TreeWalk(repository)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (FileMode.TREE.equals(tw.getFileMode(0))) {
                    collectTreeRecursive(repository, rw, tw.getObjectId(0), objects);
                }
                // skip blobs: blob:none, fetched on demand via promisor
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