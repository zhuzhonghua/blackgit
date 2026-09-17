package com.black.trim;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mapping virtual commit -> (real commit, path), kept in memory.
 *
 * A virtual commit carries the original message verbatim (no blackgit-trim
 * marker), so the reverse operation (unapply) cannot learn the source commit
 * or the trimmed path from the object itself. Instead the server records the
 * relationship here the moment it creates a virtual commit: one TSV line per
 * virtual commit
 *
 *     <vc>\t<real>\t<path>\n
 *
 * archived as "blackgit-trim-map.tsv" inside the repository's git dir (the same
 * repo the unapply protocol runs against). Virtual commits are deterministic,
 * so re-recording the same (real, path) is idempotent.
 *
 * A later unapply request names the virtual base commit `from`; the server
 * looks it up here to recover the real source commit and the path, then splices
 * the pushed subtree back at that path.
 *
 * Storage model: memory first. On the first access to a repository the TSV file
 * is read once into a static per-repo cache; from then on the in-memory map is
 * the source of truth and the file is only rewritten as a temporary archive
 * when a mapping changes. No lock is taken — the cache is a concurrent map and
 * publishes the loaded snapshot atomically. This is a stopgap until the mapping
 * moves to RPC, so nothing beyond this simple archive is needed today.
 */
public final class TrimMap {

    private static final String FILE = "blackgit-trim-map.tsv";

    /** Per-repo in-memory cache: git dir -> (vc sha -> {real sha, path}). */
    private static final ConcurrentMap<File, ConcurrentMap<String, String[]>> CACHE =
            new ConcurrentHashMap<>();

    private TrimMap() {
    }

    /** The map file, living inside the git dir. */
    static File file(Repository repo) {
        return new File(repo.getDirectory(), FILE);
    }

    /**
     * Returns the in-memory mapping for {@code repo}, reading the TSV file on
     * first access. The snapshot is published atomically, so concurrent first
     * accesses agree on the same map without a lock; the file is not consulted
     * again afterwards.
     */
    private static ConcurrentMap<String, String[]> entries(Repository repo) throws IOException {
        File f = file(repo);
        ConcurrentMap<String, String[]> cached = CACHE.get(f);
        if (cached != null) {
            return cached;
        }
        ConcurrentMap<String, String[]> loaded = load(f);
        ConcurrentMap<String, String[]> prev = CACHE.putIfAbsent(f, loaded);
        return prev != null ? prev : loaded;
    }

    private static ConcurrentMap<String, String[]> load(File f) throws IOException {
        ConcurrentMap<String, String[]> map = new ConcurrentHashMap<>();
        if (!f.isFile()) {
            return map;
        }
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                map.put(parts[0], new String[]{parts[1], parts[2]});
                    }
                }
        return map;
            }

    /**
     * Records {@code vc -> (real, path)} in memory and archives the mapping to
     * the TSV file. Deterministic trims produce the same line, so re-recording
     * is idempotent (the key is the virtual commit id).
     */
    public static void record(Repository repo, ObjectId vc, ObjectId real, String path)
            throws IOException {
        ConcurrentMap<String, String[]> map = entries(repo);
        map.put(vc.getName(), new String[]{real.getName(), path});
        archive(file(repo), map);
        }

    /**
     * Archives the in-memory mapping back to the TSV file. Purely a temporary
     * persistence for the memory-first model; superseded by RPC later.
     */
    private static void archive(File f, ConcurrentMap<String, String[]> map) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String[]> e : new TreeMap<>(map).entrySet()) {
            String[] v = e.getValue();
            lines.add(e.getKey() + "\t" + v[0] + "\t" + v[1]);
        }
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
    }

    /** Returns {real sha, path} for the given virtual commit, or null when unknown. */
    public static String[] lookup(Repository repo, ObjectId vc) throws IOException {
        return entries(repo).get(vc.getName());
    }
}
