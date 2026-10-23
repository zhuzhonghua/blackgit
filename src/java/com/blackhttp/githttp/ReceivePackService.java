package com.blackhttp.githttp;

import com.black.FileLocks;
import com.black.GitProtocolException;
import com.black.GitRepo;
import com.black.Log;
import com.black.OriginBackfill;
import com.black.OriginProxy;
import com.black.RepoAuthz;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReceivePackService {
    private final File gitDir;
    private final Config config;

    ReceivePackService(File gitDir, Config config) {
        this.gitDir = gitDir;
        this.config = config;
    }

    GitResponse receive(InputStream in, String user, String authz) {
        if (config.readOnly) {
            Log.logger.warn("push rejected (read-only) for {}", gitDir);
            return GitResponse.error(HttpResponseStatus.FORBIDDEN, "push is disabled");
        }

        Repository repo;
        try {
            repo = GitRepo.open(gitDir);
        } catch (IOException e) {
            Log.logger.error("cannot open repo {}: {}", gitDir, e.toString());
            return GitResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString());
        }

        // 1. Local authorization: when the repo has per-user authz files, the
        //    user must have a push grant; otherwise the server-wide read-only
        //    flag above is the only gate.
        if (RepoAuthz.hasAuthz(repo) && !RepoAuthz.canPush(repo, user)) {
            Log.logger.warn("push denied for user={}: no push grant in {}",
                    user, gitDir);
            return GitResponse.error(HttpResponseStatus.FORBIDDEN,
                    "push denied: no push rights for " + user);
        }

        // 2. Buffer the request body so we can forward it to origin.
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            Log.logger.warn("receive-pack body read failed: {}", e.toString());
            return GitResponse.error(HttpResponseStatus.BAD_REQUEST,
                    "cannot read push body: " + e.getMessage());
        }

        // 3. Lock check: dry-run locally with a pre-receive hook that diffs
        //    each ref update and rejects changes to files locked by others.
        //    The dry-run also writes the new objects into the local object db.
        try {
            LockCheck lc = checkLocks(gitDir, body, user);
            if (!lc.violations.isEmpty()) {
                Log.logger.warn("push rejected by locks for user={}: {}", user, lc.violations);
                // Answer with the dry-run's own receive-pack report instead of a
                // bare HTTP error. A 403 body never reaches the user's terminal:
                // git just reports "RPC failed" and the real reason is lost. With
                // a well-formed report git prints the reason ("remote: file lock:
                // ... (locked by alice)") plus the usual [remote rejected] line,
                // the same way a pre-receive hook failure does on a normal server.
                return GitResponse.raw(HttpResponseStatus.OK,
                        "application/x-git-receive-pack-result", lc.report);
            }
        } catch (GitProtocolException e) {
            Log.logger.error("lock check could not run (push ALLOWED, locks NOT enforced) "
                    + "{}: {}", gitDir, e.toString());
        } catch (Exception e) {
            Log.logger.error("lock check failed (push ALLOWED, locks NOT enforced) "
                    + "{}: {}", gitDir, e.toString());
        }

        // 4. When the cache has a remote.origin.url, proxy the push to
        //    GitHub/GitLab using the client's token; otherwise apply locally.
        String origin = OriginProxy.originUrl(repo);
        if (origin == null || origin.isEmpty()) {
            return localReceive(body, user);
        }

        // 4. Forward to origin, then archive the pushed objects locally.
        try {
            HttpResponse<byte[]> resp = OriginProxy.forwardPost(
                    origin, "/git-receive-pack", body, authz,
                    "application/x-git-receive-pack-request");
            int code = resp.statusCode();
            String ct = resp.headers()
                    .firstValue("Content-Type")
                    .orElse("application/x-git-receive-pack-result");
            Log.logger.info("receive-pack proxied to {} -> {} for user={}",
                    origin, code, user);

            // Only archive when origin actually accepted the push. Feed the same
            // push body through the local JGit receive-pack: the body already
            // carries every new object the client uploaded, so the local cache
            // gets the commit/trees/blobs without a second round-trip to origin.
            if (code >= 200 && code < 300) {
                try (ByteArrayInputStream localIn = new ByteArrayInputStream(body);
                     SpooledBuffer localOut = new SpooledBuffer(config.spoolMemoryLimit)) {
                    com.black.ReceivePackService.receive(gitDir, localIn, localOut, user);
                    Log.logger.info("local cache archived by replaying receive-pack for {}",
                            gitDir);
                } catch (Exception e) {
                    // Severe: the push landed on origin but the local cache did
                    // not get it. Force-refresh the local cache from origin so we
                    // converge. The client already got origin's success, so this
                    // is logged and self-healed, not surfaced as a push failure.
                    Log.logger.error("post-push local replay FAILED for {}, "
                            + "force-refreshing from origin: {}", gitDir, e.toString(), e);
                    try {
                        OriginBackfill.forceFetchFromOrigin(repo, authz);
                    } catch (Exception e2) {
                        Log.logger.error("FORCE refresh from origin also failed "
                                + "for {}: {}", gitDir, e2.toString(), e2);
                    }
                }
                try {
                    com.black.BlobAllowlist.invalidate(repo);
                } catch (Exception ignore) {
                    // best-effort
                }
            }
            return GitResponse.raw(HttpResponseStatus.valueOf(code), ct, resp.body());
        } catch (GitProtocolException e) {
            Log.logger.warn("receive-pack origin error {} : {}", gitDir, e.getMessage());
            return GitResponse.error(HttpResponseStatus.BAD_GATEWAY, e.getMessage());
        } catch (Exception e) {
            Log.logger.error("receive-pack proxy failed {} : {}", gitDir, e.toString(), e);
            return GitResponse.error(HttpResponseStatus.BAD_GATEWAY,
                    "origin push failed: " + e.getMessage());
        }
    }

    /** Applies the push locally (no origin configured). */
    private GitResponse localReceive(byte[] body, String user) {
        SpooledBuffer out = new SpooledBuffer(config.spoolMemoryLimit);
        try {
            com.black.ReceivePackService.receive(gitDir,
                    new java.io.ByteArrayInputStream(body), out, user);
            Log.logger.info("receive-pack applied locally {} bytes to {}", out.size(), gitDir);
            return GitResponse.ok("application/x-git-receive-pack-result", out);
        } catch (Exception e) {
            closeQuietly(out);
            Log.logger.error("local receive-pack failed {} : {}", gitDir, e.toString(), e);
            return GitResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString());
        }
    }

    /**
     * Outcome of the lock dry-run. When {@code violations} is non-empty,
     * {@code report} holds the receive-pack result the dry-run produced for the
     * refused push, framed the way git expects, so it can be returned verbatim.
     */
    private static final class LockCheck {
        final List<String> violations;
        final byte[] report;

        LockCheck(List<String> violations, byte[] report) {
            this.violations = violations;
            this.report = report;
        }
    }

    /**
     * Dry-run the push locally to check file locks. Receives the new objects,
     * runs a pre-receive hook that diffs each ref update against the lock
     * table, then rolls refs back to their old values (so the dry-run is
     * invisible). Returns the list of lock violations (empty = OK); when there
     * are violations it also returns the report the client should see.
     */
    @SuppressWarnings("unchecked")
    private static LockCheck checkLocks(File gitDir, byte[] body, String pusher)
            throws Exception {
        Repository repo = GitRepo.open(gitDir);
        FileLocks locks = new FileLocks(gitDir);
        java.util.List<String> violations = new java.util.ArrayList<>();
        java.util.List<ReceiveCommand> applied = new java.util.ArrayList<>();
        String[] rejection = new String[1];

        ReceivePack rp = new ReceivePack(repo);
        rp.setBiDirectionalPipe(false);
        rp.setTimeout(0);
        // Only an atomic receive-pack honours rejections set by the
        // pre-receive hook; without it JGit applies the refs anyway and the
        // lock check silently does nothing.
        rp.setAtomic(true);
        rp.setPreReceiveHook((rpArg, cmds) -> {
            try (RevWalk rw = new RevWalk(repo)) {
                for (ReceiveCommand cmd : cmds) {
                    applied.add(cmd);
                    ObjectId oldId = cmd.getOldId();
                    ObjectId newId = cmd.getNewId();
                    if (newId.equals(ObjectId.zeroId())) {
                        continue; // branch deletion — not checked here
                    }
                    Set<String> changed = changedPaths(repo, rw, oldId, newId);
                    violations.addAll(locks.findViolations(changed, pusher));
                }
                if (!violations.isEmpty() && rejection[0] == null) {
                    // Reject the whole batch the way a pre-receive hook does, and
                    // put the reason into the report-status "ng <ref> <reason>"
                    // line so git prints it inside the line users already know:
                    //   ! [remote rejected] twilight -> twilight (file lock: x.umap (locked by alice))
                    // JGit frames and band-multiplexes the report itself.
                    rejection[0] = "file lock: " + String.join("; ", violations);
                    for (ReceiveCommand c : cmds) {
                        if (c.getResult() == ReceiveCommand.Result.NOT_ATTEMPTED) {
                            c.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
                                    rejection[0]);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        SpooledBuffer out = new SpooledBuffer(1 << 20);
        byte[] report;
        try {
            rp.receive(new ByteArrayInputStream(body), out, null);
            report = violations.isEmpty() ? null : out.input().readAllBytes();
        } finally {
            rollbackDryRun(gitDir, applied);
            closeQuietly(out);
        }
        return new LockCheck(violations, report);
    }

    /**
     * Undoes the ref updates a lock dry-run applied.
     *
     * <p>Two JGit details make this less obvious than it looks:
     * <ul>
     *   <li>the undo needs its own {@link Repository}: the shared instance in
     *       {@link GitRepo} keeps a ref snapshot alive for the JVM lifetime and
     *       never sees the refs the dry-run just wrote;</li>
     *   <li>it has to go through a {@link BatchRefUpdate}. A single
     *       {@code RefUpdate.delete()} answers {@code REJECTED} in this
     *       configuration -- silently, without throwing -- so the rollback would
     *       look like it worked while every refused push left its branch behind
     *       on the server.</li>
     * </ul>
     */
    private static void rollbackDryRun(File gitDir, List<ReceiveCommand> applied) {
        List<ReceiveCommand> undo = new java.util.ArrayList<>();
        for (ReceiveCommand cmd : applied) {
            if (cmd.getNewId() != null && cmd.getResult() == ReceiveCommand.Result.OK) {
                // old = what the dry-run wrote, new = what was there before.
                // For a branch the push created, "new" is zero: that is a delete.
                undo.add(new ReceiveCommand(cmd.getNewId(), cmd.getOldId(),
                        cmd.getRefName()));
            }
        }
        if (undo.isEmpty()) {
            return;
        }
        try (Repository rollback = GitRepo.openUncached(gitDir)) {
            BatchRefUpdate batch = rollback.getRefDatabase().newBatchUpdate();
            // Putting the previous tip back is by definition a non-fast-forward
            // update; without this JGit refuses the undo itself.
            batch.setAllowNonFastForwards(true);
            batch.setRefLogMessage("blackgit: rollback lock dry-run", false);
            for (ReceiveCommand cmd : undo) {
                batch.addCommand(cmd);
            }
            batch.execute(new RevWalk(rollback), NullProgressMonitor.INSTANCE);
            for (ReceiveCommand cmd : undo) {
                if (cmd.getResult() != ReceiveCommand.Result.OK) {
                    Log.logger.warn("rollback of dry-run ref {} failed: {} (now {})",
                            cmd.getRefName(), cmd.getResult(),
                            rollback.exactRef(cmd.getRefName()));
                }
            }
        } catch (Exception e) {
            Log.logger.warn("cannot roll back the lock dry-run of {}: {}",
                    gitDir, e.toString());
        }
    }

    /** Computes repo-relative paths changed between old and new (tree diff). */
    private static Set<String> changedPaths(Repository repo, RevWalk rw,
                                            ObjectId oldId, ObjectId newId) throws IOException {
        Set<String> paths = new HashSet<>();
        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        try (TreeWalk tw = new TreeWalk(repo)) {
            if (!oldId.equals(ObjectId.zeroId())) {
                RevCommit oldCommit = rw.parseCommit(oldId);
                oldTreeIter.reset(repo.newObjectReader(), oldCommit.getTree());
            } else {
                oldTreeIter.reset();
            }
            RevCommit newCommit = rw.parseCommit(newId);
            newTreeIter.reset(repo.newObjectReader(), newCommit.getTree());
            if (oldId.equals(ObjectId.zeroId())) {
                tw.addTree(new EmptyTreeIterator());
            } else {
                tw.addTree(oldTreeIter);
            }
            tw.addTree(newTreeIter);
            tw.setRecursive(true);
            while (tw.next()) {
                paths.add(tw.getPathString());
            }
        }
        return paths;
    }

    private static void closeQuietly(SpooledBuffer out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // no-op
        }
    }
}
