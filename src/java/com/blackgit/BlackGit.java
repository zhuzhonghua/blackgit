package com.blackgit;

import com.black.Log;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;

public class BlackGit {
    public static BlackGit bg = null;

    public String repoPath = null;
    public Repository repository = null;

    public BlackGit(String path) throws Exception {
        repoPath = path;

        repository = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build();
        Log.logger.info("the git repo {}", repoPath);
        String head = getHeadTarget();
        if (!head.endsWith("main") && !head.endsWith("master")) {
            throw new Exception("no head in "+head+" "+repoPath);
        }
    }

    public String getHeadTarget() throws Exception {
        String full = repository.getFullBranch();
        if (full != null && full.startsWith("refs/heads/")) {
            return full;
        }
        throw new Exception("no head in "+repoPath);
    }
}
