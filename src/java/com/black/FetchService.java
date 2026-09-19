package com.black;

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
import java.util.HashSet;
import java.util.Set;

public class FetchService {
    public static final int MAX_COMMITS = 10;

    public static byte[] fetch(Repository repository, String[] shas, String authz) throws IOException {
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
        // Shas whose on-demand backfill has already been attempted, so a fetch
        // that genuinely cannot be obtained from origin fails fast instead of
        // looping. Backfill is per-sha (not a one-shot unshallow): the client
        // asked for one sha, the server pulls only that sha from origin.
        Set<String> backfilled = new HashSet<>();
        for (String sha : shaList) {
            if (already.contains(sha))
                continue;
            already.add(sha);
            ObjectId id = ObjectId.fromString(sha.trim());

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
                    // The requested object (or a tree/blob under it) is missing
                    // locally. Pull just this sha from origin on demand rather
                    // than unshallowing the whole cached repository.
                    if (backfilled.add(id.name())) {
                        Log.logger.info("on-demand backfill for missing {} from origin", id.name());
                        if (!OriginBackfill.ensureSha(repository, id, authz)) {
                            throw e;
                        }
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

    private static void collectTreeRecursive(Repository repository, RevWalk rw, ObjectId treeId,
                                        Set<RevObject> objects) throws IOException {
        objects.add(rw.parseTree(treeId));
        try (TreeWalk tw = new TreeWalk(repository)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (FileMode.TREE.equals(tw.getFileMode(0))) {
                    collectTreeRecursive(repository, rw, tw.getObjectId(0), objects);
                }
            }
        }
    }
}
