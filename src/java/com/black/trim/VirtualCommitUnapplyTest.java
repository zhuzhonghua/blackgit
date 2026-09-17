package com.black.trim;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Self-contained tests for {@link VirtualCommitUnapply}. No server, no
 * network: run with `lein run -m com.black.trim.VirtualCommitUnapplyTest`.
 *
 * Covers: mapping a single virtual child back onto the real tree, preserving
 * unrelated paths, preserving the author/committer/message, parent linkage to
 * the source commit, chained commits, determinism, branch fast-forward +
 * conflict detection and rejection of non-virtual bases.
 */
public class VirtualCommitUnapplyTest {

    private static final PersonIdent CLIENT = new PersonIdent("client", "client@example.com");
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("blackgit-unapply-test").toFile();
        Git git = Git.init().setDirectory(dir).call();
        Repository repo = git.getRepository();
        try {
            runTests(repo);
        } finally {
            git.close();
        }
        if (failures != 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    private static void runTests(Repository repo) throws Exception {
        ObjectId source = buildFixture(repo); // real commit S

        ObjectId vc = VirtualCommit.get(repo, source, "sub").commit;
        check(!vc.equals(source), "virtual base differs from source");

        // --- a client commit on top of the virtual base, changing sub/b.txt ---
        ObjectId c1 = childCommit(repo, vc, "sub/b.txt",
                "changed!\n".getBytes(StandardCharsets.UTF_8), "client change one");
        ObjectId expectedSubBlob = repo.resolve(c1.name() + ":sub/b.txt");

        VirtualCommitUnapply.Result r1 = VirtualCommitUnapply.unapply(repo, vc, c1, "refs/heads/main");
        check(r1.count == 1, "one real commit mapped");
        check(r1.commit.equals(repo.resolve("refs/heads/main")), "branch advanced to mapped tip");

        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit R1 = rw.parseCommit(r1.commit);
            check(R1.getParents().length == 1 && R1.getParents()[0].getId().equals(source),
                    "mapped commit parents == source commit");
            check("client change one".equals(R1.getFullMessage().trim()), "client message preserved");
        }
        check(repo.resolve(r1.commit.name() + ":sub/b.txt").equals(expectedSubBlob),
                "changed subtree blob written back at sub/b.txt");
        check(blobText(repo, r1.commit, "sub/b.txt").equals("changed!\n"), "sub/b.txt content");
        check(blobText(repo, r1.commit, "hello.txt").equals("hello\n"), "hello.txt preserved");
        check(blobText(repo, r1.commit, "other/o.txt").equals("o\n"), "other/o.txt preserved");
        check(repo.resolve(r1.commit.name() + ":sub/deep/c.txt") != null, "deep/c.txt still present");

        // --- deterministic remap of the same virtual push (no branch update) ---
        VirtualCommitUnapply.Result again = VirtualCommitUnapply.unapply(repo, vc, c1, null);
        check(again.commit.equals(r1.commit), "unapply is deterministic");

        // --- chained virtual commits map to a chain ---
        ObjectId c2 = childCommit(repo, c1, "sub/b.txt",
                "even more\n".getBytes(StandardCharsets.UTF_8), "client change two");
        org.eclipse.jgit.lib.RefUpdate chainRef = repo.updateRef("refs/heads/chain");
        chainRef.setNewObjectId(source);
        chainRef.update();
        VirtualCommitUnapply.Result r2 = VirtualCommitUnapply.unapply(repo, vc, c2, "refs/heads/chain");
        check(r2.count == 2, "two real commits mapped for a chain");
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit R2 = rw.parseCommit(r2.commit);
            check(R2.getParents().length == 1, "second mapped commit has one parent");
        }
        check(repo.resolve(r2.commit.name() + "^").equals(r1.commit),
                "second mapped commit parents == first mapped commit");
        check(blobText(repo, r2.commit, "sub/b.txt").equals("even more\n"), "chain content mapped");

        // --- conflict: branch moved since base -> refuse ---
        expectConflict(repo, vc, c1, "refs/heads/main", "branch no longer on base is rejected");

        // --- non-virtual base is rejected ---
        expectFailure(repo, source, c1, "real commit as base is rejected");
        expectFailure(repo, c1, c1, "c1 is not a virtual base");
    }

    private static ObjectId buildFixture(Repository repo) throws Exception {
        PersonIdent p = new PersonIdent("t", "t@t");
        try (ObjectInserter ins = repo.newObjectInserter()) {
            ObjectId hello = ins.insert(Constants.OBJ_BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
            ObjectId bTxt = ins.insert(Constants.OBJ_BLOB, "world\n".getBytes(StandardCharsets.UTF_8));
            ObjectId oTxt = ins.insert(Constants.OBJ_BLOB, "o\n".getBytes(StandardCharsets.UTF_8));

            TreeFormatter deep = new TreeFormatter();
            deep.append("c.txt", FileMode.REGULAR_FILE,
                    ins.insert(Constants.OBJ_BLOB, "c\n".getBytes(StandardCharsets.UTF_8)));
            ObjectId deepTree = ins.insert(deep);

            TreeFormatter sub = new TreeFormatter();
            sub.append("b.txt", FileMode.REGULAR_FILE, bTxt);
            sub.append("deep", FileMode.TREE, deepTree);
            ObjectId subTree = ins.insert(sub);

            TreeFormatter other = new TreeFormatter();
            other.append("o.txt", FileMode.REGULAR_FILE, oTxt);
            ObjectId otherTree = ins.insert(other);

            TreeFormatter root = new TreeFormatter();
            root.append("hello.txt", FileMode.REGULAR_FILE, hello);
            root.append("sub", FileMode.TREE, subTree);
            root.append("other", FileMode.TREE, otherTree);
            ObjectId rootTree = ins.insert(root);

            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(rootTree);
            cb.setAuthor(p);
            cb.setCommitter(p);
            cb.setMessage("fixture\n");
            ObjectId c = ins.insert(cb);
            ins.flush();

            org.eclipse.jgit.lib.RefUpdate ref = repo.updateRef("refs/heads/main");
            ref.setNewObjectId(c);
            ref.update();
            return c;
        }
    }

    /** Builds a child of {@code base} whose tree copies the base tree with {@code path} replaced. */
    private static ObjectId childCommit(Repository repo, ObjectId base, String path,
                                        byte[] content, String msg) throws Exception {
        try (ObjectInserter ins = repo.newObjectInserter()) {
            ObjectId newBlob = ins.insert(Constants.OBJ_BLOB, content);
            ObjectId baseTree = repo.resolve(base.name() + ":");
            ObjectId newTree = putBlob(repo, baseTree, Arrays.asList(path.split("/")), newBlob, ins);

            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(newTree);
            cb.setParentId(base);
            cb.setAuthor(CLIENT);
            cb.setCommitter(CLIENT);
            cb.setMessage(msg + "\n");
            ObjectId c = ins.insert(cb);
            ins.flush();
            return c;
        }
    }

    /** New tree = {@code tree} with the blob at {@code segs} replaced by {@code blob}. */
    private static ObjectId putBlob(Repository repo, ObjectId tree, List<String> segs,
                                    ObjectId blob, ObjectInserter ins) throws Exception {
        TreeFormatter f = new TreeFormatter();
        boolean replaced = false;
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(false);
            while (tw.next()) {
                String name = tw.getNameString();
                if (name.equals(segs.get(0))) {
                    ObjectId entry;
                    FileMode mode;
                    if (segs.size() == 1) {
                        entry = blob;
                        mode = FileMode.REGULAR_FILE;
                    } else {
                        entry = putBlob(repo, tw.getObjectId(0), segs.subList(1, segs.size()),
                                blob, ins);
                        mode = FileMode.TREE;
                    }
                    f.append(name, mode, entry);
                    replaced = true;
                } else {
                    f.append(name, tw.getFileMode(0), tw.getObjectId(0));
                }
            }
        }
        if (!replaced) {
            throw new IOException("no path '" + String.join("/", segs) + "' in tree " + tree.name());
        }
        return ins.insert(f);
    }

    private static String blobText(Repository repo, ObjectId commit, String path) throws Exception {
        ObjectId blob = repo.resolve(commit.name() + ":" + path);
        ObjectLoader loader = repo.open(blob);
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }

    private static void expectConflict(Repository repo, ObjectId from, ObjectId to, String branch,
                                       String name) {
        try {
            VirtualCommitUnapply.unapply(repo, from, to, branch);
            check(false, name);
        } catch (IOException e) {
            check(e.getMessage().contains("fast-forward"), name);
        } catch (Exception e) {
            check(false, name + " " + e);
        }
    }

    private static void expectFailure(Repository repo, ObjectId from, ObjectId to, String name) {
        try {
            VirtualCommitUnapply.unapply(repo, from, to, "refs/heads/main");
            check(false, name);
        } catch (IOException e) {
            check(true, name);
        } catch (Exception e) {
            check(false, name + " " + e);
        }
    }

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            System.err.println("FAIL " + name);
            failures++;
        }
    }
}
