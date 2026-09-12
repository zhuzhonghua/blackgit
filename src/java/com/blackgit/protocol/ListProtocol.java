package com.blackgit.protocol;

import com.blackgit.BlackGit;
import com.blackgit.Log;
import com.blackgit.OriginBackfill;
import com.blackgit.SocketClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ListProtocol implements Protocol {
    @Override
    public String name() {
        return "list";
    }
    
    @Override
    public void handle(SocketClient client, ByteBuffer data) throws Exception {
        Log.logger.debug("list request from {}", client.addr);
        Map<String, String> nameId = new TreeMap<>(getList(BlackGit.bg.repository));
        String headTarget = getHeadTarget(BlackGit.bg.repository, nameId);
        Log.logger.debug("list reply {} refs from repo {}, HEAD -> {}",
                nameId.size(), BlackGit.bg.repoPath, headTarget);
        for (Map.Entry<String, String> entry : nameId.entrySet()) {
            Log.logger.debug("list ref {} {}", entry.getValue(), entry.getKey());
        }
        StringBuilder sb = new StringBuilder();
        sb.append('@').append(headTarget).append(' ').append("HEAD").append('\n');
        for (Map.Entry<String, String> entry : nameId.entrySet()) {
            sb.append(entry.getValue());
            sb.append(' ');
            sb.append(entry.getKey());
            sb.append('\n');
        }
        client.write(sb.toString());
    }

    public Map<String, String> getList(Repository repository) throws Exception {
        Map<String, String> branchCommitMap = listLocalBranches(repository);
        if (branchCommitMap.isEmpty() && OriginBackfill.hasOrigin(repository)) {
            try {
                OriginBackfill.fetchFromOrigin(repository);
                OriginBackfill.checkoutTrackingBranches(repository);
                branchCommitMap = listLocalBranches(repository);
            } catch (Exception e) {
                Log.logger.warn("list backfill from origin failed: {}", e.toString());
            }
        }
        return branchCommitMap;
    }

    private static Map<String, String> listLocalBranches(Repository repository) throws Exception {
        Git git = new Git(repository);
        List<Ref> call = git.branchList().setListMode(null).call();

        Map<String, String> branchCommitMap = new HashMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            for (Ref ref : call) {
                String branchName = ref.getName();
                if (!branchName.startsWith("refs/heads/")) {
                    continue;
                }

                RevCommit commit = walk.parseCommit(repository.resolve(branchName));
                String commitId = commit.getName();

                branchCommitMap.put(branchName, commitId);
            }
        }
        return branchCommitMap;
    }

    public String getHeadTarget(Repository repository, Map<String, String> nameId) throws Exception {
        String full = repository.getFullBranch();
        if (full != null && full.startsWith("refs/heads/") && nameId.containsKey(full)) {
            return full;
        }
        throw new Exception("no head in "+BlackGit.bg.repoPath);
    }
}