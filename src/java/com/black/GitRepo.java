package com.black;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.pack.PackConfig;

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
        if (!ensureBitmap(created)) {
            Log.logger.warn("lazy blob/tree fetches may fail: no pack bitmap for {}", gitDir);
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

    /**
     * Ensures the repository has a pack bitmap index. JGit's upload-pack uses
     * the bitmap to answer "is this non-advertised object reachable?" when a
     * client wants a historical tree/blob (lazy fetch); without it the
     * request is refused with HTTP 500. When no {@code *.bitmap} file exists
     * in {@code objects/pack}, repacks with JGit's own GC (PackConfig with
     * bitmap building enabled — the in-process equivalent of
     * {@code git repack -ad --write-bitmap-index}; no external git binary
     * needed). Idempotent: a no-op once a bitmap exists. Called automatically
     * on first open, and exposed publicly so other entry points (e.g. a
     * repo-setup/admin interface) can invoke it directly.
     *
     * @return true when a bitmap already existed or was built successfully
     */
    public static boolean ensureBitmap(Repository repo) {
        File packDir = new File(repo.getDirectory(), "objects/pack");
        if (hasBitmap(packDir)) {
            return true;
        }
        if (!(repo instanceof FileRepository)) {
            Log.logger.warn("cannot build bitmap for non-file repository {}",
                    repo.getDirectory());
            return false;
        }
        GC gc = new GC((FileRepository) repo);
        PackConfig pc = new PackConfig(repo);
        pc.setBuildBitmaps(true);
        gc.setPackConfig(pc);
        try {
            gc.repack();
            Log.logger.info("built pack bitmap index for {}", repo.getDirectory());
            return true;
        } catch (IOException e) {
            Log.logger.error("bitmap repack failed for {}: {}",
                    repo.getDirectory(), e.toString());
            return false;
        }
    }

    private static boolean hasBitmap(File packDir) {
        File[] files = packDir.listFiles((d, n) -> n.endsWith(".bitmap"));
        return files != null && files.length > 0;
    }
}