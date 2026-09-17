package com.black;

import com.black.trim.VirtualCommitUnapply;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PackParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class UnapplyService {

    public static Result unapply(Repository repository, String from, String to, String branch)
            throws IOException {
        ObjectId fromId = ObjectId.fromString(from.trim());
        ObjectId toId = ObjectId.fromString(to.trim());
        VirtualCommitUnapply.Result result =
                VirtualCommitUnapply.unapply(repository, fromId, toId, branch);
        return new Result(result.commit.name(), result.count, result.base.name());
    }

    public static void importPack(Repository repository, byte[] pack) throws IOException {
        try (ObjectInserter ins = repository.newObjectInserter();
             InputStream in = new ByteArrayInputStream(pack)) {
            PackParser parser = ins.newPackParser(in);
            parser.setAllowThin(false);
            parser.setLockMessage("unapply push");
            parser.parse(NullProgressMonitor.INSTANCE);
            ins.flush();
        }
    }

    public static final class Result {
        public final String commitSha;
        public final int count;
        public final String fromSha;

        public Result(String commitSha, int count, String fromSha) {
            this.commitSha = commitSha;
            this.count = count;
            this.fromSha = fromSha;
        }
    }
}