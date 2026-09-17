package com.black;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;

public final class UploadPackService {

    private UploadPackService() {
    }

    /**
     * Serves an upload-pack (fetch) request.
     *
     * @param gitDir     the repository directory (bare, worktree, or linked worktree)
     * @param in         the client request body (pkt-line upload-pack request)
     * @param out        the response body stream
     * @param protocolV2 true when the client negotiated protocol v2
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void upload(File gitDir, InputStream in, OutputStream out,
                              boolean protocolV2) throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close
            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            up.setTimeout(0);
            if (protocolV2) {
                up.setExtraParameters(Collections.singleton("version=2"));
            }
            try {
                up.upload(in, out, null);
            } catch (PackProtocolException e) {
                throw new GitProtocolException(e.getMessage(), e);
            }
        }

    /**
     * Advertises upload-pack refs.
     *
     * @param gitDir     the repository directory
     * @param out        the response body stream
     * @param protocolV2 true when the client negotiated protocol v2
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void advertiseUploadPack(File gitDir, OutputStream out,
                                           boolean protocolV2) throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close
            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            if (protocolV2) {
                up.setExtraParameters(Collections.singleton("version=2"));
            }
            PacketLineOut pckOut = new PacketLineOut(out);
            RefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(pckOut);
            up.sendAdvertisedRefs(adv, "git-upload-pack");
        }
    }
