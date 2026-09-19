package com.black;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-file locks, stored as a plain text sidecar next to the bare repo:
 *
 * <pre>
 * engine/physics.cpp\tdev1\t2026-09-19T13:00:00Z
 * </pre>
 *
 * A lock means: only the locking user may push commits that touch the locked
 * path. Locks are exclusive (one path = one holder); stacking is rejected.
 */
public final class FileLocks {

    private final File store;

    public static final class Lock {
        public String user;
        public String since;
    }

    public FileLocks(File repoDir) {
        this.store = new File(repoDir, "blackw-locks.txt");
    }

    private synchronized Map<String, Lock> read() {
        Map<String, Lock> out = new LinkedHashMap<>();
        if (!store.exists()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(store.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 2) {
                    continue;
                }
                Lock l = new Lock();
                l.user = parts[1];
                l.since = parts.length >= 3 ? parts[2] : "";
                out.put(parts[0], l);
            }
        } catch (IOException e) {
            Log.logger.warn("cannot read locks {}: {}", store, e.toString());
        }
        return out;
    }

    private synchronized void write(Map<String, Lock> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Lock> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t')
              .append(e.getValue().user).append('\t')
              .append(e.getValue().since == null ? "" : e.getValue().since)
              .append('\n');
        }
        try {
            Files.writeString(store.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.logger.error("cannot write locks {}: {}", store, e.toString());
        }
    }

    /** Returns the holder of the path, or null if not locked. */
    public synchronized Lock get(String path) {
        return read().get(normalize(path));
    }

    /** Lists all locks. */
    public synchronized Map<String, Lock> list() {
        return read();
    }

    /**
     * Tries to lock path as user. Returns:
     *  - "ok"
     *  - "already-locked:<user>"
     */
    public synchronized String lock(String path, String user) {
        path = normalize(path);
        Map<String, Lock> m = read();
        Lock existing = m.get(path);
        if (existing != null) {
            return "already-locked:" + existing.user;
        }
        Lock l = new Lock();
        l.user = user;
        l.since = java.time.Instant.now().toString();
        m.put(path, l);
        write(m);
        return "ok";
    }

    /**
     * Unlocks path. Only the holder may unlock. Returns:
     *  - "ok"
     *  - "not-locked"
     *  - "not-holder:<user>"
     */
    public synchronized String unlock(String path, String user) {
        path = normalize(path);
        Map<String, Lock> m = read();
        Lock existing = m.get(path);
        if (existing == null) {
            return "not-locked";
        }
        if (!existing.user.equals(user)) {
            return "not-holder:" + existing.user;
        }
        m.remove(path);
        write(m);
        return "ok";
    }

    /**
     * Given a set of repo-relative paths changed by an incoming push, returns
     * the list of paths that are locked by someone else. Empty means the push
     * may proceed.
     */
    public synchronized List<String> findViolations(
            java.util.Set<String> changedPaths, String pusher) {
        Map<String, Lock> m = read();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Lock> e : m.entrySet()) {
            String lockedPath = e.getKey();
            String holder = e.getValue().user;
            if (holder.equals(pusher)) {
                continue;
            }
            // A locked path blocks any change under it (directory-prefix match).
            for (String changed : changedPaths) {
                if (changed.equals(lockedPath)
                        || changed.startsWith(lockedPath + "/")) {
                    violations.add(changed + " (locked by " + holder + ")");
                    break;
                }
            }
        }
        return violations;
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
