package com.blackhttp.githttp;

import com.black.Log;
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

    GitResponse advertise(String service, boolean protocolV2, boolean shallowHint) {
        try {
            if ("git-upload-pack".equals(service)) {
                Log.logger.info("info/refs advertise upload-pack v2={} shallow-hint={} from {}",
                        protocolV2, shallowHint, gitDir);
                return GitResponse.ok("application/x-git-upload-pack-advertisement",
                        advertiseUploadPack(protocolV2));
            }
            if ("git-receive-pack".equals(service)) {
                if (config.readOnly) {
                    Log.logger.warn("push advertise rejected (read-only) for {}", gitDir);
                    return GitResponse.error(HttpResponseStatus.FORBIDDEN, "push is disabled");
                }
                Log.logger.info("info/refs advertise receive-pack from {}", gitDir);
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

    private SpooledBuffer advertiseUploadPack(boolean protocolV2) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        com.black.UploadPackService.advertiseUploadPack(gitDir, buf, protocolV2);
        return toSpool(buf);
    }

    private SpooledBuffer advertiseReceivePack() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        com.black.ReceivePackService.advertiseReceivePack(gitDir, buf);
        return toSpool(buf);
    }

    private static SpooledBuffer toSpool(ByteArrayOutputStream buf) throws IOException {
        SpooledBuffer spool = new SpooledBuffer(buf.size());
        spool.write(buf.toByteArray(), 0, buf.size());
        return spool;
    }
}