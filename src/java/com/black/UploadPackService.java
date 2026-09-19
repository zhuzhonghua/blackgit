package com.black;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
     * @param blobAllow  path-based blob allowlist; empty = all blobs downloadable
     * @param user       authenticated client username (from Authorization: Basic)
     * @param authz      raw Authorization header value, forwarded to origin verbatim
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void upload(File gitDir, InputStream in, OutputStream out,
                              boolean protocolV2, List<String> blobAllow,
                              String user, String authz) throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close

        // Buffer the whole request body so we can pre-parse the client's wants
        // and pull any missing sha from origin on demand before handing the
        // body to JGit. Without this, a shallow cache that lacks a client-wanted
        // commit makes JGit reject the request outright ("want ... not valid")
        // instead of transparently fetching the missing sha upstream.
        byte[] body = in.readAllBytes();
        ShallowRequest req = FetchRequestParser.parse(
                new ByteArrayInputStream(body), protocolV2);
        backfillMissingWants(repo, req, authz);
        Log.logger.info("upload-pack served for user={} repo={} v2={} wants={}",
                user, gitDir, protocolV2, req.wants.size());

            UploadPack up = new UploadPack(repo);
            up.setBiDirectionalPipe(false);
            up.setTimeout(0);
            if (protocolV2) {
                up.setExtraParameters(Collections.singleton("version=2"));
            }
        BlobAllowlist allow = BlobAllowlist.get(repo, blobAllow);
        if (!allow.allowsAll()) {
            up.setRequestValidator((uploadPack, wants) -> checkBlobWants(repo, allow, wants));
        }
            try {
            up.upload(new ByteArrayInputStream(body), out, null);
            } catch (PackProtocolException e) {
                throw new GitProtocolException(e.getMessage(), e);
            }
        }

    /**
     * For every object the client asked for ({@code want <sha>}), fetch it from
     * origin on demand when it is missing locally. Fails softly: if origin also
     * lacks the object (or no origin is configured) the request is left for
     * JGit to reject with the usual protocol error.
     */
    private static void backfillMissingWants(Repository repo, ShallowRequest req,
                                             String authz) {
        for (ObjectId want : req.wants) {
            boolean present;
            try (ObjectReader reader = repo.newObjectReader()) {
                present = reader.has(want);
            } catch (IOException e) {
                Log.logger.warn("cannot check presence of want {}: {}", want.name(), e.toString());
                continue;
            }
            if (present) {
                continue;
            }
            Log.logger.info("want {} missing locally, on-demand backfill from origin",
                    want.name());
            if (!OriginBackfill.ensureSha(repo, want, authz)) {
                Log.logger.warn("want {} could not be backfilled from origin", want.name());
            }
        }
    }

    /**
     * Refuses blob wants whose path is not on the allowlist. Commits, tags and
     * trees are always allowed (history/structure, not content).
     */
    private static void checkBlobWants(Repository repo, BlobAllowlist allow,
                                       Collection<ObjectId> wants) throws IOException {
        try (ObjectReader reader = repo.newObjectReader()) {
            for (ObjectId id : wants) {
                int type;
                try {
                    type = reader.open(id).getType();
                } catch (MissingObjectException e) {
                    throw new PackProtocolException("want " + id.name() + " not found");
                }
                if (type == Constants.OBJ_BLOB && !allow.allows(id)) {
                    throw new PackProtocolException(
                            "blob " + id.name() + " not authorized (not on allowlist path)");
                }
            }
        }
    }

    /**
     * Advertises upload-pack refs.
     *
     * @param gitDir     the repository directory
     * @param out        the response body stream
     * @param protocolV2 true when the client negotiated protocol v2
     * @param authz      raw Authorization header, forwarded to origin when
     *                   syncing local heads to upstream so upstream authenticates
     *                   as the same user; null to use the cache repo's own creds
     * @throws GitProtocolException on a malformed client request
     * @throws Exception            on I/O or unexpected errors
     */
    public static void advertiseUploadPack(File gitDir, OutputStream out,
                                           boolean protocolV2, String authz) throws Exception {
        Repository repo = GitRepo.open(gitDir); // shared, cached — do not close
        // Keep advertised tips fresh: if upstream was pushed to directly
        // (bypassing this cache), sync local heads to origin while staying
        // shallow, so clients through the proxy see the latest tips.
        OriginBackfill.syncHeadsFromOrigin(repo, authz);
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
