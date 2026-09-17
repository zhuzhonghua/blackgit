package com.black;

import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

public final class ReceivePackService {

    private ReceivePackService() {
    }

    /**
     * Serves a receive-pack (push) request.
     *
     * @param gitDir the repository directory (bare, worktree, or linked worktree)
     * @param in     the client request body (pkt-line receive-pack request)
     * @param out    the response body stream
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void receive(File gitDir, InputStream in, OutputStream out)
            throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close
            ReceivePack rp = new ReceivePack(repo);
        rp.setAllowNonFastForwards(false); // reject --force / non-fast-forward pushes
        rp.setAllowDeletes(false);         // reject ref deletion
        rp.setAllowPushOptions(true);      // accept git push -o realcommit= / -o path=
            rp.setBiDirectionalPipe(false);
            rp.setTimeout(0);
            try {
                rp.receive(in, out, null);
            } catch (PackProtocolException e) {
                throw new GitProtocolException(e.getMessage(), e);
            }
        }

    /**
     * Advertises receive-pack refs.
     *
     * @param gitDir the repository directory
     * @param out    the response body stream
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void advertiseReceivePack(File gitDir, OutputStream out) throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close
            PacketLineOut pckOut = new PacketLineOut(out);
            RefAdvertiser adv = new RefAdvertiser.PacketLineOutRefAdvertiser(pckOut);
            pckOut.writeString("# service=git-receive-pack\n");
            pckOut.end();
            ReceivePack rp = new ReceivePack(repo);
        rp.setAllowPushOptions(true); // advertise the push-options capability to clients
            rp.setBiDirectionalPipe(false);
            rp.sendAdvertisedRefs(adv);
        }
    }
