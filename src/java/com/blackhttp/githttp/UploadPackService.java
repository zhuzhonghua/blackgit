package com.blackhttp.githttp;

import com.black.GitProtocolException;
import com.black.Log;
import com.black.ShallowRequest;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.File;
import java.io.InputStream;

final class UploadPackService {
    private final File gitDir;
    private final Config config;

    UploadPackService(File gitDir, Config config) {
        this.gitDir = gitDir;
        this.config = config;
    }

    GitResponse upload(InputStream in, boolean protocolV2, ShallowRequest shallow) {
        SpooledBuffer out = new SpooledBuffer(config.spoolMemoryLimit);
        try {
            com.black.UploadPackService.upload(gitDir, in, out, protocolV2, config.blobAllow);
            Log.logger.info("upload-pack served {} bytes from {} v2={} {}",
                    out.size(), gitDir, protocolV2, shallow.summary());
            if (Log.logger.isDebugEnabled()) {
                for (String line : shallow.requestLines) {
                    Log.logger.debug("  upload-pack request line: {}", line);
                }
            }
            return GitResponse.ok("application/x-git-upload-pack-result", out);
        } catch (GitProtocolException e) {
            closeQuietly(out);
            Log.logger.warn("upload-pack protocol error {} : {}", gitDir, e.getMessage());
            return GitResponse.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            closeQuietly(out);
            Log.logger.error("upload-pack failed {} : {}", gitDir, e.toString(), e);
            return GitResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString());
        }
    }

    private static void closeQuietly(SpooledBuffer out) {
        try {
            out.close();
        } catch (java.io.IOException ignored) {
            // no-op
        }
    }
}