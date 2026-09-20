package com.blackhttp;

import com.black.Log;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/**
 * Auto-initializes a bare repo from an upstream URL on server startup.
 *
 * <pre>
 *   --root /data/repos --upstream https://github.com/user/repo.git
 *
 *   -> /data/repos/repo/   (bare, origin = upstream, empty)
 * </pre>
 *
 * Only HTTP(S) upstreams are supported. The local repo is created empty; the
 * first client request triggers a lazy fetch from upstream (same strategy as
 * josh). If the local repo already exists, it is left untouched (idempotent).
 */
public final class RepoBootstrapper {

    private final Config config;

    public RepoBootstrapper(Config config) {
        this.config = config;
    }

    /**
     * Creates the local bare repo if missing and sets origin. Does not fetch
     * anything — the first client request pulls refs lazily.
     */
    public File bootstrap() throws IOException, InterruptedException {
        if (config.upstreamUrl == null || config.upstreamUrl.isEmpty()) {
            return null;
        }
        String upstream = config.upstreamUrl;
        String repoName = repoNameFromUrl(upstream);
        File repoDir = new File(config.repoBase, repoName);

        if (new File(repoDir, "HEAD").isFile()) {
            Log.logger.info("repo already exists at {}, skipping bootstrap", repoDir);
            return repoDir;
        }

        Log.logger.info("bootstrapping empty repo {} (origin = {}, lazy fetch on first request)",
                repoDir, upstream);
        // 1. git init --bare
        run(repoDir.getParentFile(), "git", "init", "--bare", repoDir.getName());

        // 2. git remote add origin <upstream>
        run(repoDir, "git", "remote", "add", "origin", upstream);

        Log.logger.info("bootstrap complete: {} (empty, origin set)", repoDir);
        return repoDir;
    }

    /** Extracts the repo name from a URL: .../user/repo.git -> repo. */
    static String repoNameFromUrl(String url) {
        String path = URI.create(url).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isEmpty() || "..".equals(name) || name.startsWith(".")) {
            throw new IllegalArgumentException("cannot derive repo name from URL: " + url);
        }
        return name;
    }

    private static String run(File cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IOException("command failed (" + rc + "): " + String.join(" ", cmd)
                    + "\nstdout: " + out + "\nstderr: " + err);
        }
        return out;
    }
}
