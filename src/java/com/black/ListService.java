package com.black;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListService {

    public static Map<String, String> list(Repository repository) throws Exception {
        Map<String, String> branchCommitMap = listLocalBranches(repository);
        if (branchCommitMap.isEmpty() && OriginBackfill.hasOrigin(repository)) {
            try {
                OriginBackfill.fetchFromOrigin(repository, null);
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
}
