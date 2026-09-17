package com.black.trim;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reverse of {@link VirtualCommit}: expands a pushed virtual commit (or a
 * linear chain of commits built on top of one) back into the real repository
 * history.
 *
 * A client that trimmed {@code commit S} to subtree {@code P} works in a
 * virtual world where the subtree keeps its original directory structure
 * (P is preserved in place). When it pushes {@code from=VC .. to=tip} (VC
 * being the virtual commit the server generated), each pushed virtual commit
 * V is mapped to a real commit R:
 *
 *   R.tree    = (previous real tree) with the subtree at P replaced by V.tree
 *               *at path P* (the pushed tree keeps P in the same location)
 *   R.parent  = the previously mapped real commit (S for the first one)
 *
 * The author / committer / message of V are carried over verbatim, so the
 * pushed history survives the round-trip.
 *
 * The source commit and trimmed path are recovered from the server-side
 * mapping recorded by {@link TrimMap} when the virtual commit was created
 * (the virtual commit message itself stays identical to the source's), so no
 * marker needs to be embedded in the object.
 *
 * Currently only a linear chain (one parent per commit) is mapped; merge
 * commits on top of a virtual base are rejected for now.
 */
public final class VirtualCommitUnapply {

    /** Safety bound for walking the pushed virtual chain. */
    public static final int MAX_CHAIN = 1000;

    public static final class Result {
        /** New real tip commit (equals the source commit if nothing was pushed). */
        public final ObjectId commit;
        /** Number of real commits created on top of the source commit. */
        public final int count;
        /** The source (real) commit the virtual base was trimmed from. */
        public final ObjectId base;

        public Result(ObjectId commit, int count, ObjectId base) {
            this.commit = commit;
            this.count = count;
            this.base = base;
        }
    }

    private VirtualCommitUnapply() {
    }

    /**
     * Maps {@code from}..{@code to} (virtual) back onto the real history.
     *
     * @param repo   the real hosting repository (both objects must be present)
     * @param from   virtual base commit (must carry the {@link VirtualCommit} marker)
     * @param to     pushed tip commit (a descendant of {@code from})
     * @param branch real branch to advance (or null/empty to auto-detect)
     */
    public static Result unapply(Repository repo, ObjectId from, ObjectId to, String branch)
            throws IOException {
        Base base;
        List<RevCommit> chain;
        try (RevWalk rw = new RevWalk(repo)) {
            base = parseBase(repo, rw, from);
            if (from.equals(to)) {
                // Nothing new was pushed: the tip is the base itself.
                return new Result(base.source, 0, base.source);
            }
            chain = walkChain(rw, from, to);
        }
        if (chain.size() <= 1) { // unreachable; defensive
            throw new IOException("chain from " + from.name() + " to " + to.name() + " is empty");
        }

        ObjectId tip = mapChain(repo, base, chain);

        String target = branch;
        if (target == null || target.isEmpty()) {
            target = detectBranch(repo, base.source);
        }
        if (target != null && !target.isEmpty()) {
            updateBranch(repo, target, base.source, tip);
        }

        return new Result(tip, chain.size() - 1, base.source);
    }

    private static final class Base {
        final RevCommit source;     // real commit the virtual base was trimmed from
        final List<String> segments; // path P as path segments

        Base(RevCommit source, List<String> segments) {
            this.source = source;
            this.segments = segments;
        }
    }

    private static Base parseBase(Repository repo, RevWalk rw, ObjectId from) throws IOException {
        String[] mapping = TrimMap.lookup(repo, from);
        if (mapping == null) {
            throw new IOException("base commit " + from.name()
                    + " is not a blackgit trim virtual commit (no mapped source commit)");
        }
        ObjectId sourceId = ObjectId.fromString(mapping[0]);
        String path = mapping[1];

        RevCommit source;
        try {
            source = rw.parseCommit(sourceId);
        } catch (Exception e) {
            throw new IOException("base commit " + from.name()
                    + " references unknown source commit " + sourceId.name(), e);
        }

        ObjectId realSubtree = repo.resolve(source.getName() + ":" + path);
        if (realSubtree == null) {
            throw new IOException("source commit " + sourceId.name() + " has no path " + path);
        }
        RevCommit fc = rw.parseCommit(from);
        ObjectId virtualSubtree = repo.resolve(fc.getName() + ":" + path);
        if (virtualSubtree == null || !realSubtree.equals(virtualSubtree)) {
            throw new IOException("base commit " + from.name()
                    + " tree does not match source " + sourceId.name() + ":" + path);
        }

        List<String> segments = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("base commit " + from.name() + " trims the whole tree ('.')"
                    + " which is an identity view, not a virtual commit");
        }
        return new Base(source, segments);
    }

    /** Walks the linear parent chain from {@code to} down to {@code from}. */
    private static List<RevCommit> walkChain(RevWalk rw, ObjectId from, ObjectId to)
            throws IOException {
        List<RevCommit> reverse = new ArrayList<>();
        RevCommit cur = rw.parseCommit(to);
        int guard = 0;
        while (true) {
            reverse.add(cur);
            if (cur.getId().equals(from)) {
                break;
            }
            RevCommit[] parents = cur.getParents();
            if (parents.length == 0) {
                throw new IOException("commit " + cur.name() + " is root — never reaches base " + from.name());
            }
            if (parents.length > 1) {
                throw new IOException("merge commit " + cur.name()
                        + " on top of a virtual base is not supported yet");
            }
            if (++guard > MAX_CHAIN) {
                throw new IOException("virtual chain longer than " + MAX_CHAIN);
            }
            cur = rw.parseCommit(parents[0]); // parents from getParents() may be lazily unparsed
        }
        Collections.reverse(reverse);
        return reverse; // reverse[0] == from
    }

    /** Maps each virtual commit after the base to a real commit, deepest first. */
    private static ObjectId mapChain(Repository repo, Base base, List<RevCommit> chain)
            throws IOException {
        ObjectId prevRealTree = base.source.getTree().getId();
        ObjectId prevRealCommit = base.source;

        try (ObjectInserter ins = repo.newObjectInserter()) {
            for (int i = 1; i < chain.size(); i++) {
                RevCommit v = chain.get(i);

                String path = String.join("/", base.segments);
                ObjectId pushedSubtree = repo.resolve(v.getName() + ":" + path);
                if (pushedSubtree == null) {
                    throw new IOException("pushed commit " + v.getName()
                            + " has no subtree at path " + path);
                }
                ObjectId mappedTree = replaceSubtree(repo, prevRealTree, base.segments, 0,
                        pushedSubtree, ins);

                CommitBuilder cb = new CommitBuilder();
                cb.setTreeId(mappedTree);
                cb.setParentId(prevRealCommit);
                cb.setAuthor(new PersonIdent(v.getAuthorIdent()));
                cb.setCommitter(new PersonIdent(v.getCommitterIdent()));
                cb.setEncoding(StandardCharsets.UTF_8);
                cb.setMessage(v.getFullMessage());

                ObjectId real = ins.insert(cb);
                prevRealTree = mappedTree;
                prevRealCommit = real;
            }
            ins.flush();
        }
        return prevRealCommit;
    }

    /**
     * Returns a new tree identical to {@code baseTree} except that the subtree
     * at {@code segments[idx..]} is replaced by {@code replacement}.
     */
    private static ObjectId replaceSubtree(Repository repo, ObjectId baseTree,
                                           List<String> segments, int idx,
                                           ObjectId replacement, ObjectInserter ins)
            throws IOException {
        TreeFormatter f = new TreeFormatter();
        boolean replaced = false;
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(baseTree);
            tw.setRecursive(false);
            while (tw.next()) {
                String name = tw.getNameString();
                if (!replaced && name.equals(segments.get(idx))) {
                    if (idx == segments.size() - 1) {
                        f.append(name, FileMode.TREE, replacement);
                    } else {
                        if (!FileMode.TREE.equals(tw.getFileMode(0))) {
                            throw new IOException("path segment '" + name
                                    + "' in the real tree is not a directory");
                        }
                        ObjectId child = replaceSubtree(repo, tw.getObjectId(0), segments, idx + 1,
                                replacement, ins);
                        f.append(name, FileMode.TREE, child);
                    }
                    replaced = true;
                } else {
                    f.append(name, tw.getFileMode(0), tw.getObjectId(0));
                }
            }
        }
        if (!replaced) {
            throw new IOException("unapply path '" + String.join("/", segments)
                    + "' does not exist in the real tree");
        }
        return ins.insert(f);
    }

    /** Finds a local branch whose tip is the source commit, if unambiguous. */
    private static String detectBranch(Repository repo, ObjectId source) throws IOException {
        String found = null;
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/heads/")) {
            if (source.equals(ref.getObjectId())) {
                if (found != null) {
                    throw new IOException("ambiguous branch pointing at " + source.name()
                            + " (both " + found + " and " + ref.getName() + "); pass branch=");
                }
                found = ref.getName();
            }
        }
        return found;
    }

    /** Advances the real branch only if it still points at the source commit. */
    private static void updateBranch(Repository repo, String branch, ObjectId base, ObjectId tip)
            throws IOException {
        String full = branch.startsWith("refs/") ? branch : "refs/heads/" + branch;
        Ref ref = repo.findRef(full);
        ObjectId current = ref == null ? null : ref.getObjectId();
        if (current == null) {
            throw new IOException("no ref " + full + " to update; expected base " + base.name());
        }
        if (!base.equals(current)) {
            throw new IOException("ref " + full + " is at " + current.name()
                    + ", not the expected base " + base.name()
                    + "; the pushed change is not a fast-forward");
        }
        RefUpdate update = repo.updateRef(full);
        update.setRefLogMessage("unapply virtual push " + tip.name(), false);
        update.setExpectedOldObjectId(base);
        update.setNewObjectId(tip);
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.FORCED) {
            throw new IOException("cannot update " + full + ": " + result);
        }
    }
}
