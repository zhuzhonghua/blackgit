package com.blackgit;

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
    }
}
