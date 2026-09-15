package com.blackhttp.githttp;

import com.blackhttp.Config;
import com.blackhttp.SpooledBuffer;
import com.black.Log;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

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
        try (Repository repo = GitRepo.open(gitDir)) {
            GitRepo.configureUploadPack(repo);
            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            if (protocolV2) {
                up.setExtraParameters(java.util.Collections.singleton("version=2"));
            }
            RefAdvertiser adv =
                    new RefAdvertiser.PacketLineOutRefAdvertiser(new PacketLineOut(buf));
            up.sendAdvertisedRefs(adv, "git-upload-pack");
        }
        return toSpool(buf);
    }

    private SpooledBuffer advertiseReceivePack() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PacketLineOut pckOut = new PacketLineOut(buf);
        RefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(pckOut);
        pckOut.writeString("# service=git-receive-pack\n");
        pckOut.end();
        try (Repository repo = GitRepo.open(gitDir)) {
            ReceivePack rp = new ReceivePack(repo);
            rp.setBiDirectionalPipe(false);
            rp.sendAdvertisedRefs(adv);
        }
        return toSpool(buf);
    }

    private static SpooledBuffer toSpool(ByteArrayOutputStream buf) throws IOException {
        SpooledBuffer spool = new SpooledBuffer(buf.size());
        spool.write(buf.toByteArray(), 0, buf.size());
        return spool;
    }
}