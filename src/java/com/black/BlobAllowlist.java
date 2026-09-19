package com.black;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Path-based blob allowlist for upload-pack. When the allowlist is non-empty,
 * only blobs that appear at one of the allowed repo-relative paths in any
 * reachable commit are downloadable; every other blob is refused on the wire.
 * Commits and trees are always allowed (they carry history/structure, not
 * file content), so `git log`, navigation and lazy tree fetches keep working.
 *
 * The allowed-blob set is computed lazily per (repository, allowlist) and
 * cached; {@link #invalidate} is called after a push updates the repository.
 */
public final class BlobAllowlist {

    private static final ConcurrentMap<String, BlobAllowlist> CACHE = new ConcurrentHashMap<>();

    private final Repository repo;
    private final List<String> allowPaths;
    private volatile Set<ObjectId> allowedBlobs;
    private volatile Set<ObjectId> refTips;
    private volatile boolean computed;

    private BlobAllowlist(Repository repo, List<String> allowPaths) {
        this.repo = repo;
        this.allowPaths = List.copyOf(allowPaths);
    }

    public static BlobAllowlist get(Repository repo, List<String> allowPaths) {
        String key = repo.getDirectory().getAbsolutePath() + "\u0000" + allowPaths;
        return CACHE.computeIfAbsent(key, k -> new BlobAllowlist(repo, allowPaths));
    }

    public static void invalidate(Repository repo) {
        String prefix = repo.getDirectory().getAbsolutePath() + "\u0000";
        int before = CACHE.size();
        CACHE.keySet().removeIf(k -> k.startsWith(prefix));
        Log.logger.info("blob allowlist invalidated for {} (cache {} -> {})",
                repo.getDirectory(), before, CACHE.size());
    }

    public static List<String> append(List<String> base, String path) {
        ArrayList<String> n = new ArrayList<>(base);
        n.add(path);
        return List.copyOf(n);
    }

    /** True when no allowlist is configured: every blob is downloadable. */
    public boolean allowsAll() {
        return allowPaths.isEmpty();
    }

    /** True when the given blob is reachable at an allowed path (or no allowlist). */
    public boolean allows(ObjectId id) {
        if (allowsAll()) {
            return true;
        }
        if (!computed || refsChanged()) {
            compute();
        }
        return allowedBlobs.contains(id);
    }

    /**
     * Detects whether any ref moved since the allowlist was computed. Cheap:
     * JGit's ref database is stat-cached, so this is a hash-compare of the
     * current ref tips; it also covers repositories mutated behind the
     * server's back (e.g. a local-path push).
     */
    private boolean refsChanged() {
        Set<ObjectId> cur = new HashSet<>();
        try {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                if (ref.getObjectId() != null) {
                    cur.add(ref.getObjectId());
                }
            }
        } catch (IOException e) {
            return true; // be safe: recompute
        }
        return refTips == null || !refTips.equals(cur);
    }

    private void compute() {
        Set<ObjectId> blobs = new HashSet<>();
        Set<String> want = new HashSet<>(allowPaths);
        Set<ObjectId> tips = new HashSet<>();
        try (RevWalk rw = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                ObjectId tip = ref.getObjectId();
                if (tip == null) {
                    continue;
                }
                tips.add(tip);
                try {
                    rw.markStart(rw.parseCommit(tip));
                } catch (Exception ignore) {
                    // refs that do not point at commits are irrelevant here
                }
            }
            RevCommit c;
            while ((c = rw.next()) != null) {
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(c.getTree());
                    tw.setRecursive(true);
                    while (tw.next()) {
                        if (isAllowed(tw.getPathString())) {
                            blobs.add(tw.getObjectId(0));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot build blob allowlist", e);
        }
        allowedBlobs = blobs;
        refTips = tips;
        computed = true;
        Log.logger.info("blob allowlist computed for {}: {} allowlisted blob(s) from paths {}",
                repo.getDirectory(), blobs.size(), allowPaths);
    }

    /** A blob path is allowed when it equals an allowed prefix or lives under one. */
    private boolean isAllowed(String path) {
        for (String ap : allowPaths) {
            if (path.equals(ap) || path.startsWith(ap + "/")) {
                return true;
            }
        }
        return false;
    }
}
