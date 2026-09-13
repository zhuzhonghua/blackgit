package com.blackhttp.githttp;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class GitRepo {
    private GitRepo() {
    }

    /**
     * Opens the repository for a resolved repo directory. The directory may be
     * a bare repository, a normal worktree (with a {@code .git} directory), or a
     * linked worktree whose {@code .git} is a {@code gitdir:} pointer file.
     */
    static Repository open(File repoDir) throws IOException {
        File gitDir = repoDir.getAbsoluteFile();
        File dotGit = new File(gitDir, ".git");
        if (dotGit.isDirectory()) {
            gitDir = dotGit;
        } else if (dotGit.isFile()) {
            String content = Files.readString(dotGit.toPath(), StandardCharsets.UTF_8);
            if (content.startsWith("gitdir:")) {
                File linked = new File(content.substring("gitdir:".length()).trim());
                gitDir = linked.isAbsolute() ? linked : new File(gitDir, linked.getPath());
            }
        }
        Repository repo = new FileRepository(gitDir);
        repo.incrementOpen();
        return repo;
    }
}