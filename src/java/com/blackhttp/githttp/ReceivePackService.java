package com.blackhttp.githttp;

import com.black.GitProtocolException;
import com.black.GitRepo;
import com.black.Log;
import com.black.OriginBackfill;
import com.black.OriginProxy;
import com.black.RepoAuthz;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

import org.eclipse.jgit.lib.Repository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

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

        // 3. When the cache has a remote.origin.url, proxy the push to
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
                    // The push itself succeeded on origin; a local replay hiccup
                    // must not turn a successful push into an error for the client.
                    Log.logger.warn("post-push local archive failed (push still "
                            + "OK): {}", e.toString());
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

    private static void closeQuietly(SpooledBuffer out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // no-op
        }
    }
}