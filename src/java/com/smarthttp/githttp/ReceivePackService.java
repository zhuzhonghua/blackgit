package com.smarthttp.githttp;

import com.smarthttp.Config;
import com.smarthttp.SpooledBuffer;
import com.blackgit.Log;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

final class ReceivePackService {
    private final File gitDir;
    private final Config config;

    ReceivePackService(File gitDir, Config config) {
        this.gitDir = gitDir;
        this.config = config;
    }

    GitResponse receive(InputStream in) {
        if (config.readOnly) {
            Log.logger.warn("push rejected (read-only) for {}", gitDir);
            return GitResponse.error(HttpResponseStatus.FORBIDDEN, "push is disabled");
        }
        SpooledBuffer out = new SpooledBuffer(config.spoolMemoryLimit);
        try (Repository repo = GitRepo.open(gitDir)) {
            ReceivePack rp = new ReceivePack(repo);
            rp.setBiDirectionalPipe(false);
            rp.setTimeout(0);
            rp.receive(in, out, null);
            Log.logger.info("receive-pack applied {} bytes response to {}", out.size(), gitDir);
            return GitResponse.ok("application/x-git-receive-pack-result", out);
        } catch (PackProtocolException e) {
            closeQuietly(out);
            Log.logger.warn("receive-pack protocol error {} : {}", gitDir, e.getMessage());
            return GitResponse.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            closeQuietly(out);
            Log.logger.error("receive-pack failed {} : {}", gitDir, e.toString(), e);
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