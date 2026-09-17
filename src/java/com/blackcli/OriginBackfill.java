package com.blackcli;

import com.black.Log;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

import java.util.List;

public class OriginBackfill {
    public static boolean hasOrigin(Repository repository) {
        try {
            String url = repository.getConfig().getString("remote", "origin", "url");
            return url != null && !url.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static void fetchFromOrigin(Repository repository) throws Exception {
        Log.logger.info("backfill from origin for repo {}",
                repository.getDirectory());
        boolean shallow = new java.io.File(repository.getDirectory(), "shallow").exists();
        new Git(repository).fetch().setRemote("origin").setUnshallow(shallow).call();
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
