package com.blackhttp.githttp;

import com.black.GitRepo;
import com.black.Log;
import com.black.OriginProxy;
import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

final class InfoRefsService {
    private final File gitDir;
    private final Config config;

    InfoRefsService(File gitDir, Config config) {
        this.gitDir = gitDir;
        this.config = config;
    }

    GitResponse advertise(String service, boolean protocolV2, boolean shallowHint,
                         String user, String authz) {
        try {
            if ("git-upload-pack".equals(service)) {
                Log.logger.info("info/refs advertise upload-pack v2={} shallow-hint={} user={}",
                        protocolV2, shallowHint, user);
                return GitResponse.ok("application/x-git-upload-pack-advertisement",
                        advertiseUploadPack(protocolV2, authz));
            }
            if ("git-receive-pack".equals(service)) {
                if (config.readOnly) {
                    Log.logger.warn("push advertise rejected (read-only) for {}", gitDir);
                    return GitResponse.error(HttpResponseStatus.FORBIDDEN, "push is disabled");
                }
                // Push advertisement goes through origin so the client sees the
                // upstream's current refs, not the possibly-stale local cache.
                // Falls back to local advertisement when no origin is configured.
                String origin;
                try {
                    origin = OriginProxy.originUrl(GitRepo.open(gitDir));
                } catch (IOException e) {
                    Log.logger.error("cannot open repo {}: {}", gitDir, e.toString());
                    return GitResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            e.toString());
                }
                if (origin != null && !origin.isEmpty()) {
                    try {
                        byte[] body = OriginProxy.forwardGet(origin,
                                "/info/refs?service=git-receive-pack", authz);
                        Log.logger.info("info/refs receive-pack proxied to {} for user={}",
                                origin, user);
                        return GitResponse.ok(
                                "application/x-git-receive-pack-advertisement",
                                toSpool(body));
                    } catch (Exception e) {
                        Log.logger.warn("origin info/refs receive-pack failed ({}), "
                                + "falling back to local", e.toString());
                    }
                }
                Log.logger.info("info/refs advertise receive-pack locally for user={}", user);
                return GitResponse.ok("application/x-git-receive-pack-advertisement",
                        advertiseReceivePack());
            }
            Log.logger.warn("info/refs with unexpected service {}", service);
            return GitResponse.error(HttpResponseStatus.BAD_REQUEST,
                    "unexpected service: " + service);
        } catch (Exception e) {
            Log.logger.error("info/refs advertise {} failed : {}", service, e.toString(), e);
            return GitResponse.error(HttpResponseStatus.INTERNAL_SERVER_ERROR, e.toString());
        }
    }

    private SpooledBuffer advertiseUploadPack(boolean protocolV2, String authz) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        com.black.UploadPackService.advertiseUploadPack(gitDir, buf, protocolV2, authz);
        return toSpool(buf);
    }

    private SpooledBuffer advertiseReceivePack() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        com.black.ReceivePackService.advertiseReceivePack(gitDir, buf);
        return toSpool(buf);
    }

    private static SpooledBuffer toSpool(ByteArrayOutputStream buf) throws IOException {
        return toSpool(buf.toByteArray());
    }

    private static SpooledBuffer toSpool(byte[] data) throws IOException {
        SpooledBuffer spool = new SpooledBuffer(data.length);
        spool.write(data, 0, data.length);
        return spool;
    }
}
