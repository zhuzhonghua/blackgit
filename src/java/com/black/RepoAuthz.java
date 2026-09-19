package com.black;

import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads the SVN-style authorization file for a repository from its own
 * git directory, so per-repo rules live next to the repository instead of
 * being passed in at server start.
 *
 * <p>The file is {@code <gitDir>/blackw-authz} and uses the standard Subversion
 * {@code authz} format (the same one {@link AuthzFile} parses): groups,
 * per-path sections, {@code @group} / {@code *} principals, {@code r}/{@code w}
 * rights. When the file is absent the server falls back to the global
 * {@code --blob-allow} list.
 *
 * <p>Files are parsed once per repository and cached; edit the file and the
 * server picks up the new rules on the next request (clear the JVM to force a
 * fresh read).
 */
public final class RepoAuthz {

    private static final String FILE = "blackw-authz";
    private static final ConcurrentMap<String, AuthzFile> CACHE =
            new ConcurrentHashMap<>();

    private RepoAuthz() {
    }

    /** True when this repository carries its own blackw-authz file. */
    public static boolean hasAuthz(Repository repo) {
        return new File(repo.getDirectory(), FILE).isFile();
    }

    /**
     * Readable path prefixes for the user, or null when the repository has no
     * blackw-authz file (caller should fall back to the global allowlist).
     */
    public static List<String> allowedReadPaths(Repository repo, String user) {
        AuthzFile a = load(repo);
        return a == null ? null : a.allowedReadPaths(user);
    }

    /** True when the user has write (push) rights anywhere in this repository. */
    public static boolean canPush(Repository repo, String user) {
        AuthzFile a = load(repo);
        return a != null && a.canPush(user);
    }

    private static AuthzFile load(Repository repo) {
        File f = new File(repo.getDirectory(), FILE);
        if (!f.isFile()) {
            return null;
        }
        String key = f.getAbsolutePath();
        AuthzFile cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            AuthzFile parsed = AuthzFile.load(f.toPath());
            CACHE.put(key, parsed);
            Log.logger.info("blackw-authz loaded from {}", f);
            return parsed;
        } catch (Exception e) {
            Log.logger.warn("cannot read {}: {}", f, e.toString());
            return null;
        }
    }
}
