package com.black.trim;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Self-contained tests for {@link VirtualCommit}. No server, no network:
 * run with `lein run -m com.black.trim.VirtualCommitTest`.
 *
 * Covers: subtree trim, message preservation (no marker), parent preservation,
 * determinism, the "." identity path, and failure on a missing / non-tree path.
 */
public class VirtualCommitTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("blackgit-trim-test").toFile();
        Git git = Git.init().setDirectory(dir).call();
        Repository repo = git.getRepository();
        try {
            runTests(repo);
        } finally {
            git.close(); // closes the underlying repository
        }
        if (failures != 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }

    private static void runTests(Repository repo) throws Exception {
        PersonIdent person = new PersonIdent("tester", "tester@example.com");

        ObjectId hello;
        ObjectId bTxt;
        ObjectId deepTree;
        ObjectId subTree;
        ObjectId c1;
        ObjectId c2;
        try (ObjectInserter ins = repo.newObjectInserter()) {
            hello = ins.insert(Constants.OBJ_BLOB, "hello".getBytes(StandardCharsets.UTF_8));
            bTxt = ins.insert(Constants.OBJ_BLOB, "world".getBytes(StandardCharsets.UTF_8));

            TreeFormatter deep = new TreeFormatter();
            deep.append("c.txt", FileMode.REGULAR_FILE,
                    ins.insert(Constants.OBJ_BLOB, "deep".getBytes(StandardCharsets.UTF_8)));
            deepTree = ins.insert(deep);

            TreeFormatter sub = new TreeFormatter();
            sub.append("b.txt", FileMode.REGULAR_FILE, bTxt);
            sub.append("deep", FileMode.TREE, deepTree);
            subTree = ins.insert(sub);

            TreeFormatter root = new TreeFormatter();
            root.append("hello.txt", FileMode.REGULAR_FILE, hello);
            root.append("sub", FileMode.TREE, subTree);
            root.append("other", FileMode.TREE, subTree); // reuse same subtree
            ObjectId rootTree = ins.insert(root);

            CommitBuilder cb1 = new CommitBuilder();
            cb1.setTreeId(rootTree);
            cb1.setAuthor(person);
            cb1.setCommitter(person);
            cb1.setMessage("first commit\n");
            c1 = ins.insert(cb1);

            CommitBuilder cb2 = new CommitBuilder();
            cb2.setTreeId(rootTree);
            cb2.setParentId(c1);
            cb2.setAuthor(person);
            cb2.setCommitter(person);
            cb2.setMessage("second commit\n");
            c2 = ins.insert(cb2);

            ins.flush();
        }

        org.eclipse.jgit.lib.RefUpdate ref = repo.updateRef("refs/heads/main");
        ref.setNewObjectId(c2);
        ref.update();

        check(subTree.equals(repo.resolve(c2.name() + ":sub")), "resolve source:sub");

        VirtualCommit.Result trim = VirtualCommit.get(repo, c2, "sub");
        check(!trim.commit.equals(c2), "virtual commit differs from source");
        check(trim.tree.equals(repo.resolve(trim.commit.name() + ":")), "trim.tree is the virtual root tree");
        List<String> rootNames = new ArrayList<>();
        ObjectId keptSub = null;
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(trim.tree);
            tw.setRecursive(false);
            while (tw.next()) {
                rootNames.add(tw.getNameString());
                keptSub = tw.getObjectId(0);
            }
        }
        check(rootNames.equals(Arrays.asList("sub")), "only 'sub' kept at the virtual root",
                rootNames.toString());
        check(subTree.equals(keptSub), "kept subtree id == source:sub");

        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit vc = rw.parseCommit(trim.commit);
            check(vc.getTree().getId().equals(trim.tree), "virtual root tree is the trim view");
            RevCommit[] parents = vc.getParents();
            check(parents.length == 1 && parents[0].getId().equals(c1),
                    "virtual commit keeps the original parent");
            check("tester".equals(vc.getAuthorIdent().getName())
                            && "tester@example.com".equals(vc.getAuthorIdent().getEmailAddress()),
                    "author preserved");
            check(vc.getFullMessage().equals("second commit\n"),
                    "virtual commit keeps the original message (no marker)");

            String[] mapped = TrimMap.lookup(repo, trim.commit);
            check(mapped != null
                            && mapped[0].equals(c2.getName())
                            && mapped[1].equals("sub"),
                    "virtual -> (source, path) recorded in TrimMap");
        }

        VirtualCommit.Result deep = VirtualCommit.get(repo, c2, "sub/deep");
        check(!deep.commit.equals(c2), "deep trim differs from source");
        check(deepTree.equals(repo.resolve(deep.commit.name() + ":sub/deep")),
                "deep trim keeps subtree at sub/deep");
        List<String> subNames = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(repo.resolve(deep.commit.name() + ":sub"));
            tw.setRecursive(false);
            while (tw.next()) {
                subNames.add(tw.getNameString());
            }
        }
        check(subNames.equals(Arrays.asList("deep")), "deep trim keeps only deep under sub/",
                subNames.toString());

        VirtualCommit.Result again = VirtualCommit.get(repo, c2, "sub");
        check(again.commit.equals(trim.commit), "deterministic across calls");

        VirtualCommit.Result sameSub = VirtualCommit.get(repo, c2, "./sub/");
        check(sameSub.commit.equals(trim.commit), "path normalization (./sub/)");

        VirtualCommit.Result identity = VirtualCommit.get(repo, c2, ".");
        check(identity.commit.equals(c2), "'.' path is identity commit");
        check(identity.tree.equals(repo.resolve(c2.name() + ":")), "'.' path is identity tree");

        expectFailure(repo, c2, "nope", "missing path throws");
        expectFailure(repo, c2, "sub/b.txt", "file path (not a tree) throws");
    }

    private static void expectFailure(Repository repo, ObjectId c, String path, String name) {
        try {
            VirtualCommit.get(repo, c, path);
            check(false, name);
        } catch (IOException e) {
            check(true, name);
        }
    }

    private static void check(boolean cond, String name, String... extra) {
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            System.err.println("FAIL " + name + (extra.length > 0 ? " " + extra[0] : ""));
            failures++;
        }
    }
}
