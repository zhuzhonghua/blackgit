package com.blackhttp.githttp;

import com.black.GitProtocolException;
import com.black.Log;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

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
        try {
            com.black.ReceivePackService.receive(gitDir, in, out);
            Log.logger.info("receive-pack applied {} bytes response to {}", out.size(), gitDir);
            return GitResponse.ok("application/x-git-receive-pack-result", out);
        } catch (GitProtocolException e) {
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