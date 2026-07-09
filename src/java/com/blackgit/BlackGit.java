package com.blackgit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

import com.google.protobuf.ByteString;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

public class BlackGit {
    public static BlackGit bg = null;

    private String repoPath = null;
    private Repository repository = null;

    public BlackGit(String path) throws Exception {
        repoPath = path;

        repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build();
        Log.logger.info("the git repo {}", repoPath);
    }

    public Map<String, String> getList() throws Exception {
        Git git = new Git(repository);
        RevWalk walk = new RevWalk(repository);
        List<Ref> call = git.branchList().setListMode(null).call();

        Map<String, String> branchCommitMap = new HashMap<>();
        for (Ref ref : call) {
            String branchName = ref.getName();

            // 将引用解析为提交对象
            RevCommit commit = walk.parseCommit(repository.resolve(branchName));
            String commitId = commit.getName(); // 完整的 Commit ID (40位)

            branchCommitMap.put(branchName, commitId);
        }
        return branchCommitMap;
    }

    public Message.Fetch.Builder getFetch(Message.Fetch fetch) throws IOException {
        Set<ObjectId> wants = new HashSet<>();
        for (Message.DFetchItem item : fetch.getShasList()) {
            wants.add(ObjectId.fromString(item.getSha()));
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PackWriter pw = new PackWriter(repository);
        pw.setDeltaBaseAsOffset(true);
        pw.preparePack(NullProgressMonitor.INSTANCE, wants, Collections.emptySet());
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);

        Message.Fetch.Builder sfetch = Message.Fetch.newBuilder();
        sfetch.setPack(ByteString.copyFrom(baos.toByteArray()));
        return sfetch;
    }

    public Message.Fetch.Builder getFetch1(Message.Fetch fetch) throws IOException {
        RevWalk walk = new RevWalk(repository);
        PackWriter pw = new PackWriter(repository);
        // markStart 所有请求的 commit
        for (Message.DFetchItem item : fetch.getShasList()) {
            walk.markStart(walk.parseCommit(ObjectId.fromString(item.getSha())));
        }
        // 遍历所有 commit，构建 RevObject 列表
        List<RevObject> commits = new ArrayList<>();
        for (RevCommit c : walk) {
            commits.add(c);
        }
        // preparePack(Iterator<RevObject>) 只打包传入的对象，不自动添加 tree/blob
        pw.preparePack(commits.iterator());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);

        Message.Fetch.Builder sfetch = Message.Fetch.newBuilder();
        sfetch.setPack(ByteString.copyFrom(baos.toByteArray()));
        return sfetch;
    }

    public Message.Fetch.Builder getFetch2(Message.Fetch fetch) throws IOException {
        PackWriter pw = new PackWriter(repository);
        Set<RevObject> objects = new HashSet<>();

        for (Message.DFetchItem item : fetch.getShasList()) {
            ObjectId id = ObjectId.fromString(item.getSha());

            // 判断对象类型
            RevWalk rw = new RevWalk(repository);
            RevObject obj = rw.parseAny(id);

            if (obj instanceof RevCommit) {
                // commit: 向前走100条
                rw.markStart((RevCommit) obj);
                int count = 0;
                for (RevCommit c : rw) {
                    if (count >= 100) break;
                    objects.add(c);
                    count++;
                }
            } else if (obj instanceof RevTree) {
                // tree: 按指定深度递归（不含 blob）
                collectTree(id, 0, 100, objects);
            } else if (obj instanceof RevBlob) {
                // blob: 只打包自身
                objects.add(obj);
            }
        }

        pw.preparePack(objects.iterator());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);

        Message.Fetch.Builder sfetch = Message.Fetch.newBuilder();
        sfetch.setPack(ByteString.copyFrom(baos.toByteArray()));
        return sfetch;
    }

    private void collectTree(ObjectId treeId,
                             int depth, int maxDepth,
                             Set<RevObject> objects) throws IOException {
        ObjectWalk ow = new ObjectWalk(repository);
        objects.add(ow.parseTree(treeId));

        if (depth >= maxDepth) return;

        try (TreeWalk tw = new TreeWalk(repository)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (FileMode.TREE.equals(tw.getFileMode(0))) {
                    collectTree(tw.getObjectId(0),
                            depth + 1, maxDepth, objects);
                }
                // blob 跳过
            }
        }
    }

    public Message.DFetchItem.Builder getFetchSha(String sha) throws IOException {
        ObjectId objectId = repository.resolve(sha);
        ObjectLoader loader = repository.open(objectId);
        int type = loader.getType();
        byte[] data = loader.getBytes();
        Message.DFetchItem.Builder item = Message.DFetchItem.newBuilder();
        item.setSha(sha);
        //item.setType(type);
        //item.setData(com.google.protobuf.ByteString.copyFrom(data));
        return item;
    }

    public Message.Fetch.Builder getFetch0(Message.Fetch fetch) throws IOException {
        Message.Fetch.Builder sfetch = Message.Fetch.newBuilder();
        //sfetch.setMethod(1);
        for (Message.DFetchItem sha : fetch.getShasList()) {
            Message.DFetchItem.Builder item = getFetchSha(sha.getSha());
            sfetch.addShas(item);
        }
        return sfetch;
    }
}
