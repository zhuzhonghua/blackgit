package com.black;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Repository holder for the internal git services.
 *
 * Repositories are cached in memory, one {@link Repository} per resolved git
 * directory, and reused across requests — no per-request
 * {@code new FileRepository} and no manual close bookkeeping. The server
 * internally serves only a handful of repositories, so a simple static map is
 * enough; instances live for the JVM lifetime (or until replaced by a
 * future RPC-backed layout).
 *
 * The directory may be a bare repository, a normal worktree (with a
 * {@code .git} directory), or a linked worktree whose {@code .git} is a
 * {@code gitdir:} pointer file; the resolved git directory is canonicalized so
 * all paths that point at the same repository share one instance. Published
 * atomically via {@code putIfAbsent}, so concurrent first opens agree on the
 * same instance without a lock.
 */
public final class GitRepo {

    private static final ConcurrentMap<String, Repository> CACHE = new ConcurrentHashMap<>();

    private GitRepo() {
    }

    /**
     * Returns the shared repository for {@code repoDir}, opening (and
     * configuring) it once on first access and reusing it afterwards. The
     * returned instance must not be closed — it is owned by the cache.
     */
    public static Repository open(File repoDir) throws IOException {
        File gitDir = resolveGitDir(repoDir);
        String key = gitDir.getCanonicalPath();
        Repository cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        FileRepository created = new FileRepository(gitDir);
        configureUploadPack(created);
        Repository prev = CACHE.putIfAbsent(key, created);
        if (prev != null) {
            created.close(); // another thread published first; drop our copy
            return prev;
        }
        return created;
    }

    /** Resolves the git dir (bare / worktree {@code .git} / linked {@code gitdir:} pointer). */
    private static File resolveGitDir(File repoDir) throws IOException {
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
        return gitDir.getCanonicalFile();
    }

    /**
     * Enables JGit's partial-clone support for this repository: object filters
     * ({@code --filter=blob:none}, {@code blob:limit}, {@code tree:...}) and
     * reachable-but-unadvertised object wants (used by the client's lazy fetch
     * after a filtered clone). JGit gates these behind {@code uploadpack.*}
     * config keys; we force them on in memory only (never written back to
     * disk) so the server behaves for every repository without touching its
     * config file. Applied once to each cached repository when it is opened.
     */
    public static void configureUploadPack(Repository repo) {
        repo.getConfig().setBoolean("uploadpack", null, "allowfilter", true);
        repo.getConfig().setBoolean("uploadpack", null, "allowreachablesha1inwant", true);
        repo.getConfig().setBoolean("uploadpack", null, "allowtipsha1inwant", true);
    }
}