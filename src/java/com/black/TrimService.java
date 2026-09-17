package com.black;

import com.black.trim.VirtualCommit;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.pack.PackWriter;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class TrimService {

    public static Result trim(Repository repository, String sha, String path) throws IOException {
        ObjectId id = ObjectId.fromString(sha.trim());
        boolean backfilled = false;
        while (true) {
            try {
                VirtualCommit.Result vcResult = VirtualCommit.get(repository, id, path);
                byte[] pack = buildPack(repository, vcResult);
                return new Result(vcResult.commit.name(), pack);
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                if (!backfilled && OriginBackfill.hasOrigin(repository)) {
                    Log.logger.info("trim backfill from origin for missing {}", id.name());
                    try {
                        OriginBackfill.fetchFromOrigin(repository);
                    } catch (Exception ex) {
                        Log.logger.warn("trim backfill from origin failed: {}", ex.toString());
                    }
                    backfilled = true;
                } else {
                    throw new IOException("trim " + id.name() + " path=" + path + ": "
                            + e.getMessage(), e);
                }
            }
        }
    }

    private static byte[] buildPack(Repository repo, VirtualCommit.Result result) throws IOException {
        Set<RevObject> objects = new HashSet<>();
        try (RevWalk rw = new RevWalk(repo)) {
            objects.add(rw.parseCommit(result.commit));
            objects.add(rw.parseTree(result.tree));
        }
        PackWriter pw = new PackWriter(repo);
        pw.preparePack(objects.iterator());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, baos);
        return baos.toByteArray();
    }

    public static final class Result {
        public final String virtualSha;
        public final byte[] pack;

        public Result(String virtualSha, byte[] pack) {
            this.virtualSha = virtualSha;
            this.pack = pack;
        }
    }
}
