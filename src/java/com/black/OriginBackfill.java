package com.black;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.util.List;

/**
 * Pulls objects from the configured {@code remote.origin.url} on demand.
 *
 * <p>The server itself serves a (possibly shallow) cache of upstream
 * repositories. A client fetches only the sha it asks for; the server must do
 * the same instead of unshallowing the whole cache. Unshallowing streams the
 * entire history of a large repo and blocks for a long time, so history is
 * kept shallow and only the objects a client actually needs are requested
 * from origin.
 */
public class OriginBackfill {

    /** Namespace used as a throwaway anchor ref when fetching one sha. */
    private static final String ON_DEMAND_REF_PREFIX = "refs/origin-on-demand/";

    public static boolean hasOrigin(Repository repository) {
        try {
            String url = repository.getConfig().getString("remote", "origin", "url");
            return url != null && !url.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetches current refs from origin <em>without</em> unshallowing the
     * repository. Only used to bootstrap an empty local ref set (ListService):
     * remote-tracking branch tips are updated so callers can list branches.
     * History stays shallow; any object a client later asks for is pulled on
     * demand by {@link #ensureSha(Repository, ObjectId)} instead of by this
     * method. Deliberately never calls {@code setUnshallow(true)}.
     */
    public static void fetchFromOrigin(Repository repository, String authz)
            throws Exception {
        Log.logger.info("ref backfill from origin for repo {}",
                repository.getDirectory());
        var fetch = new Git(repository).fetch().setRemote("origin");
        UsernamePasswordCredentialsProvider cp = credentials(authz);
        if (cp != null) {
            fetch.setCredentialsProvider(cp);
        }
        fetch.call();
    }

    /**
     * Makes sure {@code wanted} is available locally, fetching it from origin
     * on demand if it is missing.
     *
     * <p>Rather than unshallowing the whole repository, this asks origin for
     * exactly the wanted object. Because the cache keeps its shallow boundary
     * (the {@code shallow} file is untouched), origin computes and sends only
     * the objects missing between that boundary and {@code wanted}. A
     * throwaway ref under {@link #ON_DEMAND_REF_PREFIX} anchors the fetched
     * objects for the duration of the fetch and is removed afterwards.
     *
     * @param repository the local cache
     * @param wanted     the object id the client asked for
     * @param authz      the client's raw {@code Authorization: Basic} header,
     *                   forwarded to origin so upstream authenticates as the same
     *                   user that contacted blackgit; null when no credentials
     *                   should be injected (origin then uses the repo's stored
     *                   credentials if any)
     * @return true when the object was already present or was successfully
     *         fetched; false when there is no origin or the fetch failed
     */
    public static boolean ensureSha(Repository repository, ObjectId wanted, String authz) {
        if (hasObject(repository, wanted)) {
            return true;
        }
        if (!hasOrigin(repository)) {
            Log.logger.debug("no origin configured, cannot backfill sha {}",
                    wanted.name());
            return false;
        }
        String tmpRef = ON_DEMAND_REF_PREFIX + wanted.name();
        try {
            Log.logger.info("on-demand fetch sha {} from origin for {}",
                    wanted.name(), repository.getDirectory());
            RefSpec spec = new RefSpec("+" + wanted.name() + ":" + tmpRef);
            var fetch = new Git(repository).fetch()
                    .setRemote("origin")
                    .setRefSpecs(spec);
            UsernamePasswordCredentialsProvider cp = credentials(authz);
            if (cp != null) {
                fetch.setCredentialsProvider(cp);
            }
            fetch.call();
            return hasObject(repository, wanted);
        } catch (Exception e) {
            Log.logger.warn("on-demand fetch sha {} from origin failed: {}",
                    wanted.name(), e.toString());
            return false;
        } finally {
            deleteQuietly(repository, tmpRef);
        }
    }

    /**
     * Builds JGit credentials from the client's raw Authorization: Basic header,
     * so an on-demand origin fetch authenticates as the same user that talked to
     * blackgit. Returns null when the header is absent or malformed, in which
     * case JGit falls back to the repository's own stored origin credentials.
     */
    private static UsernamePasswordCredentialsProvider credentials(String authz) {
        if (authz == null) {
            return null;
        }
        if (!authz.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(java.util.Base64.getDecoder()
                    .decode(authz.substring(6).trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            return new UsernamePasswordCredentialsProvider(
                    decoded.substring(0, colon), decoded.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasObject(Repository repository, ObjectId id) {
        try (ObjectReader reader = repository.newObjectReader()) {
            return reader.has(id);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Repository repository, String ref) {
        try {
            RefUpdate ru = repository.updateRef(ref);
            ru.setForceUpdate(true);
            ru.delete();
        } catch (Exception ignored) {
            // best-effort cleanup of the temporary anchor ref
        }
    }

    public static void checkoutTrackingBranches(Repository repository) throws Exception {
        List<Ref> remotes = new Git(repository).branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE).call();
        for (Ref r : remotes) {
            String name = r.getName();
            if (!name.startsWith("refs/remotes/origin/")) {
                continue;
            }
            String shortName = name.substring("refs/remotes/origin/".length());
            if (shortName.isEmpty() || "HEAD".equals(shortName)) {
                continue;
            }
            String local = "refs/heads/" + shortName;
            if (repository.findRef(local) != null) {
                continue;
            }
            ObjectId oid = repository.resolve(name);
            if (oid == null) {
                continue;
            }
            RefUpdate update = repository.updateRef(local);
            update.setNewObjectId(oid);
            RefUpdate.Result result = update.update();
            Log.logger.info("backfill local branch {} -> {} ({})", local, oid.name(), result);
        }
    }
}