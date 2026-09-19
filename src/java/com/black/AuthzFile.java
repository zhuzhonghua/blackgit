package com.black;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SVN-style path authorization file.
 *
 * <p>Format (compatible with Subversion's {@code authz}):
 * <pre>
 * [groups]
 * devs = alice, bob
 * ops = carol
 *
 * [/]
 * * =
 *
 * [/src]
 * @devs = r
 * alice = rw
 *
 * [/docs]
 * * = r
 * </pre>
 *
 * <p>A section header is a repo-relative path ({@code [/src]} -&gt; {@code src});
 * {@code [/]} or {@code []} means the whole repository. Each line grants
 * read/write rights to a principal: a user, a group written as {@code @group},
 * or {@code *} for everyone. Rights use the letters {@code r} (read) and
 * {@code w} (write); only read matters for upload-pack, write for receive-pack.
 *
 * <p>Model is allow-only: a user gets a path prefix only when they (directly,
 * through a group, or via {@code *}) are granted it. A path with no section,
 * or a section that does not mention the user at all, is not accessible.
 */
public final class AuthzFile {

    private static final String GROUPS_SECTION = "##groups##";

    private final Map<String, List<String>> groups = new HashMap<>();
    /** section path (repo-relative, no leading slash; "" = root) -> principal -> rights */
    private final Map<String, Map<String, String>> sections = new LinkedHashMap<>();

    private AuthzFile() {
    }

    public static AuthzFile load(Path file) throws IOException {
        AuthzFile a = new AuthzFile();
        String current = null;
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String header = line.substring(1, line.length() - 1).strip();
                if (header.equals("groups")) {
                    current = GROUPS_SECTION;
                } else {
                    // Drop an optional "repo:" prefix; blackgit serves one repo
                    // mapping per repository so the path part is what matters.
                    int colon = header.indexOf(':');
                    String path = colon >= 0 ? header.substring(colon + 1) : header;
                    path = normalizePath(path);
                    current = path;
                    a.sections.computeIfAbsent(path, k -> new HashMap<>());
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String principal = line.substring(0, eq).strip();
            String rights = line.substring(eq + 1).strip();
            if (current == null) {
                continue;
            }
            if (current.equals(GROUPS_SECTION)) {
                List<String> members = new ArrayList<>();
                for (String m : principal.split(",")) {
                    m = m.strip();
                    if (!m.isEmpty()) {
                        members.add(m);
                    }
                }
                a.groups.put(principal, members);
            } else {
                a.sections.get(current).put(principal, rights);
            }
        }
        return a;
    }

    private static String normalizePath(String p) {
        p = p.trim();
        if (p.isEmpty() || p.equals("/")) {
            return "";
        }
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Returns all repo-relative path prefixes the user may read. The empty
     * string ("") means the whole repository. Empty list means nothing is
     * readable (anonymous or ungranted users get no blobs).
     */
    public List<String> allowedReadPaths(String user) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            if (hasRight(user, e.getValue(), 'r')) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** True when the user has write (push) rights on any path (root-level check). */
    public boolean canPush(String user) {
        for (Map<String, String> rights : sections.values()) {
            if (hasRight(user, rights, 'w')) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRight(String user, Map<String, String> rights, char ch) {
        String r = rights.get(user);
        if (r != null && r.indexOf(ch) >= 0) {
            return true;
        }
        for (Map.Entry<String, List<String>> ge : groups.entrySet()) {
            if (ge.getValue().contains(user)) {
                String gr = rights.get("@" + ge.getKey());
                if (gr != null && gr.indexOf(ch) >= 0) {
                    return true;
                }
            }
        }
        String all = rights.get("*");
        return all != null && all.indexOf(ch) >= 0;
    }
}
