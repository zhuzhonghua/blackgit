package com.smarthttp.githttp;

import java.io.File;

/**
 * Maps the git repository segment of a smart-HTTP URL path to a local
 * repository directory.
 *
 * <p>The client clones with a URL like {@code http://host:port/xxx.git}. The
 * server can no longer be started with a fixed repository path; instead the
 * repository name is taken from the URL ({@code xxx.git} -&gt; {@code xxx}) and
 * resolved to the directory {@code <repoBase>/<name>} (e.g.
 * {@code libgit2.git} -&gt; {@code ~/libgit2}).
 */
public final class RepoResolver {
    private final File repoBase;

    public RepoResolver(File repoBase) {
        this.repoBase = repoBase;
    }

    /** Returns the local directory for the repo named {@code name}. */
    public File resolve(String name) {
        if (name == null) {
            return null;
        }
        return new File(repoBase, name);
    }

    /**
     * Extracts the repository name from a request path such as
     * {@code /libgit2.git/info/refs}. Returns {@code null} for paths without a
     * usable repo segment.
     */
    public static String repoName(String path) {
        if (path == null) {
            return null;
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        int slash = path.indexOf('/', start);
        int end = slash < 0 ? path.length() : slash;
        String segment = path.substring(start, end);
        if (segment.isEmpty()) {
            return null;
        }
        if (segment.endsWith(".git")) {
            segment = segment.substring(0, segment.length() - 4);
        }
        if (segment.isEmpty()) {
            return null;
        }
        if (segment.startsWith(".") || segment.equals("..")
                || segment.indexOf('\\') >= 0 || segment.indexOf('/') >= 0) {
            return null;
        }
        return segment;
    }
}