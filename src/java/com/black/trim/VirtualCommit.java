package com.black.trim;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Subtree trim + virtual commit generator (josh-style "just" filter).
 *
 * For a source commit C and a path P (e.g. "sub/dir"), produces a *virtual*
 * commit whose root tree keeps the requested subtree *in place*: the directory
 * structure up to {@code C:P} is preserved (that subtree is not moved to the
 * repo root), and only the unrelated paths are trimmed away. The author,
 * committer, parents and message are carried over verbatim from C — the
 * virtual commit message is the source message, unchanged.
 *
 * The result is deterministic: the same (commit, path) always yields the same
 * virtual object id (it is a normal git object), so a virtual commit that is
 * pruned/gc'd can simply be regenerated on request — no extra bookkeeping.
 *
 * Tree serving is intentionally non-recursive for now: the caller may pack
 * only the single trimmed root tree (no sub-trees, no blobs). A
 * recursive flag can be added later as an argument here / a parameter on the
 * wire without changing the object model.
 */
public final class VirtualCommit {

    /** Identity root path: trim with path "." returns the source commit unchanged. */
    public static final String ROOT_PATH = ".";

    public static final class Result {
        /** Virtual commit id (equals the source commit id when path is "."). */
        public final ObjectId commit;
        /** Virtual commit root tree (subtree kept in place, unrelated paths trimmed). */
        public final ObjectId tree;

        public Result(ObjectId commit, ObjectId tree) {
            this.commit = commit;
            this.tree = tree;
        }
    }

    private static final ConcurrentMap<String, Result> cache = new ConcurrentHashMap<>();

    private VirtualCommit() {
    }

    /**
     * Returns a virtual commit for {@code source}:{@code path}.
     *
     * @param repo   the hosting repository (object is written into its odb)
     * @param source source commit id (and its parent tree is used to resolve the path)
     * @param path   subtree path ('.' or empty for the whole tree)
     */
    public static Result get(Repository repo, ObjectId source, String path) throws IOException {
        String p = normalizePath(path);
        String cacheKey = source.name() + "\u0000" + p;
        Result cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Result result = compute(repo, source, p);
        cache.put(cacheKey, result);
        if (!ROOT_PATH.equals(p)) {
            // Record the virtual -> (source, path) relationship so unapply can
            // recover where to splice the pushed subtree back.
            TrimMap.record(repo, result.commit, source, p);
        }
        return result;
    }

    private static Result compute(Repository repo, ObjectId source, String p) throws IOException {
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit c = rw.parseCommit(source); // MissingObjectException if absent

            if (ROOT_PATH.equals(p)) {
                // Identity view: no new object, just the source commit + its root tree.
                return new Result(source, c.getTree().getId());
            }

            ObjectId treeId = resolveTree(repo, c, p);
            rw.parseTree(treeId); // IncorrectObjectTypeException if the path names a blob

            try (ObjectInserter ins = repo.newObjectInserter()) {
                ObjectId view = trimView(repo, c.getTree().getId(), segments(p), treeId, ins);
                ObjectId virtual = buildVirtual(repo, c, p, view, ins);
                ins.flush();
                return new Result(virtual, view);
            }
        }
    }

    /**
     * Builds the virtual view root tree: a tree that mirrors the directory
     * chain leading to the trimmed subtree and nothing else, so the subtree
     * stays at its original location instead of being spliced to the repo
     * root.
     */
    private static ObjectId trimView(Repository repo, ObjectId realRoot, List<String> segs,
                                     ObjectId leaf, ObjectInserter ins) throws IOException {
        String first = segs.get(0);
        TreeFormatter f = new TreeFormatter();
        boolean found = false;
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(realRoot);
            tw.setRecursive(false);
            while (tw.next()) {
                if (tw.getNameString().equals(first)) {
                    ObjectId child;
                    if (segs.size() == 1) {
                        child = leaf;
                    } else {
                        if (!FileMode.TREE.equals(tw.getFileMode(0))) {
                            throw new IOException("path segment '" + first
                                    + "' in the trimmed tree is not a directory");
                        }
                        child = trimView(repo, tw.getObjectId(0), segs.subList(1, segs.size()),
                                leaf, ins);
                    }
                    f.append(first, FileMode.TREE, child);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw new IOException("no path segment '" + first + "' in tree " + realRoot.name());
        }
        return ins.insert(f);
    }

    private static ObjectId buildVirtual(Repository repo, RevCommit c, String path, ObjectId view,
                                     ObjectInserter ins)
            throws IOException {
        CommitBuilder cb = new CommitBuilder();
        cb.setTreeId(view);
        cb.setParentIds(c.getParents());
        // NB: PersonIdent is immutable and CommitBuilder only reads it, so pass
        // the parsed idents straight through. Do NOT use new PersonIdent(src):
        // in JGit 6.10 that constructor copies only name/email and stamps the
        // CURRENT time + local tz, which would make the virtual commit
        // non-deterministic across recomputations (the cache only masks it
        // within one process). The virtual commit must be byte-deterministic.
        cb.setAuthor(c.getAuthorIdent());
        cb.setCommitter(c.getCommitterIdent());
        cb.setEncoding(StandardCharsets.UTF_8);
        cb.setMessage(c.getFullMessage());

        return ins.insert(cb);
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    private static ObjectId resolveTree(Repository repo, RevCommit c, String path) throws IOException {
        ObjectId oid = repo.resolve(c.getName() + ":" + path);
        if (oid == null) {
            throw new IOException("no path '" + path + "' in commit " + c.getName());
        }
        return oid;
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return ROOT_PATH;
        }
        String p = path.trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() ? ROOT_PATH : p;
    }
}
