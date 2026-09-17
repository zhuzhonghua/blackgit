package com.blackgit.trim;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Local on-disk mapping virtual commit -> (real commit, path).
 *
 * A virtual commit carries the original message verbatim (no blackgit-trim
 * marker), so the reverse operation (unapply) cannot learn the source commit
 * or the trimmed path from the object itself. Instead the server records the
 * relationship here the moment it creates a virtual commit: one TSV line per
 * virtual commit
 *
 *     <vc>\t<real>\t<path>\n
 *
 * stored as "blackgit-trim-map.tsv" inside the repository's git dir (the same
 * repo the unapply protocol runs against). Virtual commits are deterministic,
 * so re-recording the same (real, path) is idempotent.
 *
 * A later unapply request names the virtual base commit `from`; the server
 * looks it up here to recover the real source commit and the path, then splices
 * the pushed subtree back at that path.
 */
public final class TrimMap {

    private static final String FILE = "blackgit-trim-map.tsv";
    private static final Object LOCK = new Object();

    private TrimMap() {
    }

    /** The map file, living inside the git dir. */
    static File file(Repository repo) {
        return new File(repo.getDirectory(), FILE);
    }

    /**
     * Records {@code vc -> (real, path)}. Rewrites the file, deduped by the
     * virtual commit id (deterministic trims produce the same line).
     */
    public static void record(Repository repo, ObjectId vc, ObjectId real, String path)
            throws IOException {
        File f = file(repo);
        String line = vc.getName() + "\t" + real.getName() + "\t" + path;
        synchronized (LOCK) {
            List<String> lines = new ArrayList<>();
            if (f.isFile()) {
                lines.addAll(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            }
            String prefix = vc.getName() + "\t";
            int i = 0;
            while (i < lines.size() && !lines.get(i).startsWith(prefix)) {
                i++;
            }
            if (i < lines.size()) {
                lines.set(i, line);
            } else {
                lines.add(line);
            }
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
        }
    }

    /** Returns {real sha, path} for the given virtual commit, or null when unknown. */
    public static String[] lookup(Repository repo, ObjectId vc) throws IOException {
        File f = file(repo);
        if (!f.isFile()) {
            return null;
        }
        String prefix = vc.getName() + "\t";
        synchronized (LOCK) {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.startsWith(prefix)) {
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        return new String[]{parts[1], parts[2]};
                    }
                }
            }
        }
        return null;
    }
}