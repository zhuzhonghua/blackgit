package com.blackhttp.githttp;

import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import com.black.Log;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UploadPack;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;

final class UploadPackService {
    private final File gitDir;
    private final Config config;

    UploadPackService(File gitDir, Config config) {
        this.gitDir = gitDir;
        this.config = config;
    }

    GitResponse upload(InputStream in, boolean protocolV2) {
        SpooledBuffer out = new SpooledBuffer(config.spoolMemoryLimit);
        try (Repository repo = GitRepo.open(gitDir)) {
            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            up.setTimeout(0);
            if (protocolV2) {
                up.setExtraParameters(Collections.singleton("version=2"));
            }
            up.upload(in, out, null);
            Log.logger.info("upload-pack served {} bytes from {} v2={}", out.size(), gitDir, protocolV2);
            return GitResponse.ok("application/x-git-upload-pack-result", out);
        } catch (PackProtocolException e) {
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