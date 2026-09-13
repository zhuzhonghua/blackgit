import os
import subprocess
import sys

def pp(line, target):
  target.write(line)
  if not line.endswith('\n'):
    target.write('\n')
  target.flush()

def pout(line):
  pp(line, sys.stdout)

def perr(line):
  pp(line, sys.stderr)

class InitBareCommand:
  def __init__(self, blackw):
    #git clone --filter=blob:none --sparse <URL>
    self.blackw = blackw
    self.usage = "usage: git blackw initbare <path>"
    self.origin = f"blackw::x.x.x.x:xxxx"

  def getpath(self, argv):
    if len(argv) != 3:
      raise Exception(f"{self.usage}")
    path = argv[2]
    if os.path.exists(path):
      raise Exception(f"{path} exists\n{self.usage}")
    os.makedirs(path)
    return os.path.realpath(path)

  def writeshallow(self, path, sha):
    shallow = os.path.join(path, ".git", "shallow")
    with open(shallow, "w") as f:
      f.write(sha + "\n")

  def run(self, argv):
    #path = self.getpath(argv)
    run = self.blackw.run_cmd
    run(['git', 'clone', '--depth=1', '--filter=blob:none', '--no-checkout'], cwd=path)
    #run(['git', 'init', '.'], cwd=path)
    #run(['git', 'remote', 'add', 'origin', f"{self.origin}"], cwd=path)
    #run(['git', 'config', 'remote.origin.promisor', 'true'], cwd=path)
    #run(['git', 'config', 'remote.origin.fetch', "+refs/heads/*:refs/remotes/origin/*"], cwd=path)
    #run(['git', 'config', 'remote.origin.partialclonefilter', 'blob:none'], cwd=path)
    #run(['git', 'config', 'sparse.expectFilesOutsideOfPatterns', 'true'], cwd=path)
    #run(['git', 'config', 'core.sparsecheckout', 'true'], cwd=path)
    #run(["git", "config", "extensions.partialclone", "origin"], cwd=path)
    #run(["git", "config", "protocol.blackw.allow", "always"], cwd=path)
    #run(['git', 'sparse-checkout', 'init', '--cone', '--sparse-index'], cwd=path)
    #run(['git', 'sparse-checkout', 'set'], cwd=path)
    #branch, sha = self.remotedefaultbranch(path)
    #self.writeshallow(path, sha)
    #run(['git', 'fetch', '--depth=1', '--update-shallow', 'origin', branch], cwd=path)
    #run(["git", "update-ref", f"refs/heads/{branch}", "FETCH_HEAD"], cwd=path)
    #run(["git", "symbolic-ref", "HEAD", f"refs/heads/{branch}"], cwd=path)
    #run(["git", "read-tree", "HEAD"], cwd=path)
    #self.hiderootfiles(path)
    #run(['git', 'checkout', branch], cwd=path)

  def hiderootfiles(self, path):
    out = self.blackw.git_output(["git", "ls-tree", "-z", f"HEAD"], cwd=path)
    for e in [x for x in out.split("\0") if x]:
      meta, _, name = e.partition("\t")
      if not name:
        continue
      parts = meta.split()
      if len(parts) != 3 or parts[1] != "blob":
        continue
      self.blackw.git_output(["git", "update-index", "--skip-worktree", f"{name}"], cwd=path)

  def remotedefaultbranch(self, path):
    out = self.blackw.git_output(['git', 'ls-remote', '--symref', 'origin', 'HEAD'],
                                 cwd=path).strip()
    lines = out.splitlines()
    branch = lines[0].split("\t")[0]
    return branch[len("ref: refs/heads/"):], lines[1].split("\t")[0]

class InitCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw init <path> [--force] [--only <dir>]"
    self.origin = f"blackw::x.x.x.x:xxxx"

  def run(self, argv):
    path, force, only = self.parseargs(argv)
    if os.path.exists(path) and not os.path.isdir(path):
      raise Exception(f"{path} is not a directory")
    if not os.path.exists(path):
      os.makedirs(path)
    if self.isrepo(path):
      if not force:
        raise Exception(f"{path} is already a git repo, use --force to refresh configs")
      self.applyconfig(path)
    else:
      self.initrepo(path, only)

  def parseargs(self, argv):
    args = argv[2:]
    force = False
    only = None
    path = None
    i = 0
    while i < len(args):
      a = args[i]
      if a == "--force":
        force = True
      elif a == "--only":
        i += 1
        if i >= len(args):
          raise Exception(f"--only needs a dir\n{self.usage}")
        if only is not None:
          raise Exception(f"{self.usage}")
        only = args[i]
      elif a in ("-h", "--help"):
        raise Exception(f"{self.usage}")
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        if path is not None:
          raise Exception(f"{self.usage}")
        path = a
      i += 1
    if not path:
      raise Exception(f"{self.usage}")
    return path, force, only

  def isrepo(self, path):
    return os.path.exists(os.path.join(path, ".git"))

  def applyconfig0(self, path):
    run = self.blackw.run_cmd
    run(["git", "config", "core.repositoryformatversion", "1"], cwd=path)
    run(['git', 'config', 'core.fsmonitor', 'true'], cwd=path)
    run(['git', 'config', 'core.multipackindex', 'true'], cwd=path)
    run(['git', 'config', 'core.commitgraph', 'true'], cwd=path)
    run(['git', 'config', 'core.sparsecheckout', 'true'], cwd=path)
    run(['git', 'config', 'sparse.expectFilesOutsideOfPatterns', 'true'], cwd=path)
    run(["git", "config", "extensions.partialclone", "origin"], cwd=path)
    run(["git", "config", "protocol.blackw.allow", "always"], cwd=path)
    run(['git', 'config', 'fetch.writecommitgraph', 'false'], cwd=path)
    run(['git', 'config', 'remote.origin.promisor', 'true'], cwd=path)
    run(['git', 'config', 'remote.origin.partialclonefilter', 'combine:blob:none+tree:0'], cwd=path)
    run(['git', 'sparse-checkout', 'init', '--cone', '--sparse-index'], cwd=path)
    run(['git', 'commit-graph', 'write', '--reachable', '--changed-paths'], cwd=path)

  def applyconfig(self, path):
    self.blackw.run_cmd(['git', 'remote', 'set-url', 'origin', f"{self.origin}"], cwd=path)
    self.applyconfig0(path)
    pout(f"refreshed configs in {path} (repo untouched: no fetch, no HEAD change)")

  def initrepo(self, path, only=None):
    run = self.blackw.run_cmd
    run(['git', 'init', '.'], cwd=path)
    run(['git', 'remote', 'add', 'origin', f"{self.origin}"], cwd=path)
    self.applyconfig0(path)
    # Server pack is truncated at MAX_COMMITS: skip the in-fetch
    # commit-graph write (it walks past the boundary while .git/shallow
    # does not exist yet -> "Could not read"), write the graph explicitly
    # after the shallow boundary is in place.
    run(['git', '-c', 'fetch.writeCommitGraph=false',
         'fetch', '--depth=1', '--update-shallow', 'origin'], cwd=path)
    self.ensure_shallow(path)
    run(['git', 'commit-graph', 'write', '--reachable', '--changed-paths'],
        cwd=path)
    self.inithead(path, only)

  def ensure_shallow(self, path):
    # Belt-and-braces: a depth-limited pack without .git/shallow looks
    # like a corrupt truncated history ("Could not read <parent>").
    # Git normally writes .git/shallow itself once the helper accepts
    # `option depth`; if it didn't, mark FETCH_HEAD as the boundary.
    import os as _os
    shallow = _os.path.join(path, ".git", "shallow")
    if _os.path.exists(shallow):
      return
    out = self.blackw.git_output(["git", "rev-parse", "FETCH_HEAD"],
                                 cwd=path).strip().splitlines()
    shas = [l.strip().split()[0] for l in out if l.strip()]
    shas = [s for s in shas if len(s) >= 40]
    if not shas:
      return
    with open(shallow, "w") as f:
      for s in dict.fromkeys(shas):
        f.write(s + "\n")
    pout(f"wrote .git/shallow with {len(shas)} boundary commit(s)")

  def remotedefaultbranch(self, path):
    out = self.git_output(['git', 'ls-remote', '--symref', 'origin', 'HEAD'],
                          cwd=path).strip()
    pout(out)

  def inithead(self, path, only=None):
    branch = self.remotedefaultbranch(path)
    run = self.blackw.run_cmd
    run(["git", "update-ref", f"refs/heads/{branch}", "FETCH_HEAD"], cwd=path)
    run(["git", "symbolic-ref", "HEAD", f"refs/heads/{branch}"], cwd=path)
    run(["git", "config", f"branch.{branch}.remote", "origin"], cwd=path)
    run(["git", "config", f"branch.{branch}.merge", f"refs/heads/{branch}"], cwd=path)
    # Pin _top() to the new repo: `path` may differ from process cwd.
    self.blackw.toplevel = os.path.realpath(os.path.abspath(path))
    if only is not None:
      rel = self.blackw.normalizerel(os.path.join(path, only))
      if rel == ".":
        raise Exception(f"--only needs a sub dir, not root\n{self.usage}")
      self.blackw.sparsify_scoped(path, rel)
    else:
      self.blackw.sparsify_all(path)

class LsCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: refer to ls-tree"

  def run(self, argv):
    args = argv[2:]
    self.blackw.run_cmd(["git", "ls-tree"] + args)

class SyncFileCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw syncfile <file>"

  def run(self, argv):
    if len(argv) != 3 or argv[2] in ("-h", "--help"):
      raise Exception(f"{self.usage}")
    bw = self.blackw
    toplevel = bw._top()
    head = bw.git_output(["git", "rev-parse", "HEAD"], cwd=toplevel).strip()
    rel = bw.normalizerel(argv[2])
    if rel == ".":
      raise Exception(f"not a file\n{self.usage}")
    pout(f"syncfile HEAD={head} toplevel={toplevel} path={rel}")
    if bw.type(rel) != "blob":
      raise Exception(f"not a file\n{self.usage}")
    out = bw.git_output(["git", "ls-tree", "HEAD", "--", rel], cwd=toplevel)
    meta, _ = out.splitlines()[0].split("\t", 1)
    mode, _, sha = meta.split()
    self.release_by_hash(sha, rel, mode)

  def release_by_hash(self, sha, rel, mode):
    bw = self.blackw
    if not bw._allowed(rel):
      return False
    toplevel = bw._top()
    otype = bw.git_output(["git", "cat-file", "-t", sha], cwd=toplevel).strip()
    if otype != "blob":
      raise Exception(f"release_by_hash only support blob, got {otype}: {sha}")
    bw.ensurepresent(sha)
    bw.run_cmd(["git", "update-index", "--add", "--cacheinfo",
                f"{mode},{sha},{rel}"], cwd=toplevel)
    bw.run_cmd(["git", "update-index", "--no-skip-worktree", "--", rel],
               cwd=toplevel)
    bw.run_cmd(["git", "checkout-index", "-f", "--", rel], cwd=toplevel)
    pout(f"released {rel} {sha}")
    return True

class FollowCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw follow [-d] <dir>"

  def run(self, argv):
    path, delete = self.parseargs(argv)
    bw = self.blackw
    toplevel = bw._top()
    rel = bw.normalizerel(path)
    if rel == ".":
      raise Exception(f"no root\n{self.usage}")
    head = bw.git_output(["git", "rev-parse", "HEAD"], cwd=toplevel).strip()
    pout(f"follow HEAD={head} toplevel={toplevel} path={rel} delete={delete}")
    if bw.type(rel) != "tree":
      raise Exception(f"not a dir {rel}\n{self.usage}")
    if delete:
      self.remove_sparse(rel)
      return
    if not bw._allowed(rel):
      pout("follow: path filtered, nothing to do")
      return
    self.ensure_sparse([rel])

  def parseargs(self, argv):
    args = argv[2:]
    delete = False
    path = None
    for a in args:
      if a == "-d":
        delete = True
      elif a in ("-h", "--help"):
        raise Exception(f"{self.usage}")
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        if path is not None:
          raise Exception(f"{self.usage}")
        path = a
    if not path:
      raise Exception(f"{self.usage}")
    return path, delete

  def remove_sparse(self, rel):
    bw = self.blackw
    toplevel = bw._top()
    dirty = bw.git_output(["git", "status", "--porcelain", "--", rel],
                          cwd=toplevel).strip()
    if dirty:
      raise Exception(f"follow: {rel} has local changes, commit/stash first:\n{dirty}")
    sparse = bw.getsparselist()
    rest = sorted(e for e in sparse if e != rel and not e.startswith(rel.rstrip("/") + "/"))
    if bw.iscovered(rel, rest, []):
      pout(f"follow: {rel} still covered by sparse {rest} "
           f"(cone mode cannot exclude sub-paths), nothing to do")
      return
    if rest == sorted(sparse):
      pout(f"follow: {rel} not in sparse, nothing to do")
      return
    if not rest:
      raise Exception("follow: cannot remove the last sparse entry "
                      "(cone-mode 'set' requires >=1 path); "
                      "use 'git sparse-checkout disable' for a full checkout")
    pout(f"sparse-checkout set {rest}")
    bw.run_cmd(["git", "sparse-checkout", "set"] + rest, cwd=toplevel)

  def ensure_sparse(self, rel_paths):
    bw = self.blackw
    toplevel = bw._top()
    sparse = bw.getsparselist()
    to_add = []
    for rel in rel_paths:
      if bw.iscovered(rel, sparse, to_add):
        continue
      to_add.append(rel)
    uniq = []
    for d in to_add:
      if d not in uniq and not any(d != x and (d == x or d.startswith(x.rstrip("/") + "/"))
                                   for x in to_add):
        uniq.append(d)
    to_add = sorted(uniq)
    if to_add:
      pout(f"sparse-checkout add {to_add}")
      bw.run_cmd(["git", "sparse-checkout", "add"] + to_add, cwd=toplevel)
    else:
      pout(f"follow: {rel_paths} already covered by sparse, nothing to do")

class SyncDirCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw syncdir <dir>"

  def run(self, argv):
    if len(argv) != 3 or argv[2] in ("-h", "--help"):
      raise Exception(f"{self.usage}")
    bw = self.blackw
    toplevel = bw._top()
    rel = bw.normalizerel(argv[2])
    if rel == ".":
      raise Exception(f"no root\n{self.usage}")
    head = bw.git_output(["git", "rev-parse", "HEAD"], cwd=toplevel).strip()
    pout(f"syncdir HEAD={head} toplevel={toplevel} path={rel}")
    if bw.type(rel) != "tree":
      raise Exception(f"not a dir {rel}\n{self.usage}")
    self.checkout_shallow(rel)

  def checkout_shallow(self, rel):
    bw = self.blackw
    toplevel = bw._top()
    if not bw._allowed(rel):
      pout("syncdir: path filtered, nothing to do")
      return
    out = bw.git_output(["git", "ls-tree", "-z", f"HEAD:{rel}"], cwd=toplevel)
    entries = [e for e in out.split("\0") if e]
    if not entries:
      pout(f"syncdir: {rel} is empty, nothing to do")
      return
    syncfile = SyncFileCommand(bw)
    done, skipped, subdirs = 0, 0, 0
    for e in entries:
      meta, _, name = e.partition("\t")
      if not name:
        continue
      mode, otype, sha = meta.split()
      child = f"{rel}/{name}"
      if otype == "blob":
        if syncfile.release_by_hash(sha, child, mode):
          done += 1
        else:
          skipped += 1
      elif otype in ("tree", "commit"):
        pout(f"skip subdir {child} (non-recursive)")
        subdirs += 1
      else:
        pout(f"skip {child} (unknown type {otype})")
        skipped += 1
    pout(f"syncdir done: released={done} filtered={skipped} skipped_subdirs={subdirs}")

class ExternalBlackW:
  def __init__(self, checkout_filter=None):
    self.toplevel = None
    self.checkout_filter = checkout_filter or (lambda rel: True)

  def run(self, argv):
    pout(f"external blackw run {argv}")
    if len(argv) < 2:
      raise Exception("usage error")
    if argv[1] == "init":
      InitCommand(self).run(argv)
    elif argv[1] == "initbare":
      InitBareCommand(self).run(argv)
    elif argv[1] == "syncfile":
      SyncFileCommand(self).run(argv)
    elif argv[1] == "syncdir":
      SyncDirCommand(self).run(argv)
    elif argv[1] == "follow":
      FollowCommand(self).run(argv)
    elif argv[1] == "ls":
      LsCommand(self).run(argv)
    else:
      raise Exception(f"unsupport operation {argv[1]}")

  def _top(self):
    if self.toplevel is None:
      self.toplevel = self.git_output(["git", "rev-parse", "--show-toplevel"]).strip()
    return self.toplevel

  def remotedefaultbranch(self, path):
    self.git_output(["git", "remote", "set-head", "origin", "-a"], cwd=path)
    out = git_output(["git", "symbolic-ref", "refs/remotes/origin/HEAD"],
                     cwd=path).strip()
    if not out.startswith("refs/remotes/origin/"):
      raise Exception(f"remote branch name broken {out}")
    return out[len("refs/remotes/origin/"):]

  def git_output(self, cmd, *arg, **args):
    pout(f"{cmd}")
    with subprocess.Popen(cmd,
                          stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE,
                          text=True,
                          *arg, **args) as p:
      out, err = p.communicate()
      if err:
        perr(err)
      if p.returncode != 0:
        raise Exception(err.strip() or f"cmd failed: {cmd}")
      return out if out else ""

  def run_cmd(self, cmd, *arg, **args):
    out = self.git_output(cmd, *arg, **args)
    if out:
      pout(out)
    return out

  def flag_skip_worktree_all(self, toplevel):
    # Flag every index entry via pipe (no ARG_MAX issue, one pass).
    # NOTE: update-index CLI only accepts exact file paths -- dirs, globs,
    # :/ and . are ignored or fatal -- so per-entry listing is required
    # here (unlike sparse-checkout, which pattern-matches internally).
    import subprocess as _sp
    ls = _sp.Popen(["git", "ls-files", "-z"],
                   stdout=_sp.PIPE, stderr=_sp.PIPE, cwd=toplevel)
    raw, ls_err = ls.communicate()
    if ls.returncode != 0:
      msg = ls_err.decode(errors="replace").strip() if ls_err else ""
      raise Exception(msg or "git ls-files failed")
    n = raw.count(b"\x00")
    if n == 0:
      return 0
    ui = _sp.Popen(["git", "update-index", "-z", "--skip-worktree", "--stdin"],
                   stdin=_sp.PIPE, stdout=_sp.PIPE, stderr=_sp.PIPE,
                   cwd=toplevel)
    _, ui_err = ui.communicate(raw)
    if ui.returncode != 0:
      msg = ui_err.decode(errors="replace").strip() if ui_err else ""
      raise Exception(msg or "git update-index failed")
    return n

  def sparsify_all(self, toplevel):
    self.run_cmd(["git", "read-tree", "HEAD"], cwd=toplevel)
    n = self.flag_skip_worktree_all(toplevel)
    pout(f"sparsified all ({n} paths, empty worktree, skip-worktree, status clean)")

  def sparsify_scoped(self, toplevel, rel):
    # Single-tree non-recursive init, same mechanics as sparsify_all:
    # full index (entry names only, no blob content), worktree gets only
    # rel's direct files; everything else --skip-worktree so status stays
    # clean. A reduced index alone can never be clean (HEAD vs index would
    # show every missing path as staged deletion). Blobs are fetched in ONE
    # pack (not one promisor roundtrip per file) and checked out batched.
    otype = self.git_output(["git", "cat-file", "-t", f"HEAD:{rel}"],
                            cwd=toplevel).strip()
    if otype != "tree":
      raise Exception(f"--only {rel} is not a dir ({otype})")
    self.run_cmd(["git", "read-tree", "HEAD"], cwd=toplevel)
    out = self.git_output(["git", "ls-tree", "-z", f"HEAD:{rel}"], cwd=toplevel)
    entries = []
    for e in [x for x in out.split("\0") if x]:
      meta, _, name = e.partition("\t")
      if not name:
        continue
      parts = meta.split()
      if len(parts) != 3 or parts[1] != "blob":
        continue
      entries.append((parts[0], parts[2], f"{rel}/{name}"))
    if entries:
      self.fetch_pack([s for _, s, _ in entries], toplevel)
      # NOTE: --cacheinfo takes exactly one mode,sha,path triplet per
      # flag occurrence; repeat the flag, do not append triplets as paths.
      cacheinfo = []
      for m, s, p in entries:
        cacheinfo += ["--cacheinfo", f"{m},{s},{p}"]
      for i in range(0, len(entries), 500):
        self.run_cmd(["git", "update-index", "--add"] +
                     cacheinfo[i * 2:(i + 500) * 2], cwd=toplevel)
      for i in range(0, len(entries), 1000):
        self.run_cmd(["git", "checkout-index", "-f", "--"] +
                     [p for _, _, p in entries[i:i + 1000]], cwd=toplevel)
    # Flag everything, then unflag the materialized files (keeps are few).
    n = self.flag_skip_worktree_all(toplevel)
    keep = [p for _, _, p in entries]
    for i in range(0, len(keep), 1000):
      self.run_cmd(["git", "update-index", "--no-skip-worktree", "--"] +
                   keep[i:i + 1000], cwd=toplevel)
    pout(f"sparsified scoped ({rel}: {len(keep)} files checked out "
         f"non-recursive, {n} skip-worktree, status clean)")

  def fetch_pack(self, shas, toplevel):
    import blackgit as _blackgit
    uniq = list(dict.fromkeys(shas))
    net = _blackgit.Net()
    try:
      pack = net.call("fetch", "\n".join(uniq).encode("utf-8"))
    finally:
      try:
        net.close()
      except Exception:
        pass
    if not pack:
      return
    import subprocess as _sp
    with _sp.Popen(["git", "index-pack", "--stdin", "--promisor"],
                   stdin=_sp.PIPE, stdout=_sp.PIPE, stderr=_sp.PIPE,
                   cwd=toplevel) as p:
      _, err = p.communicate(pack)
      if p.returncode != 0:
        msg = err.decode(errors="replace").strip() if err else ""
        raise Exception(msg or "index-pack failed")

  def normalizerel(self, p):
    if p in (".", "./", ""):
      return "."
    ap = os.path.realpath(os.path.abspath(p))
    top = os.path.realpath(os.path.abspath(self._top()))
    try:
      rel = os.path.relpath(ap, top)
    except ValueError:
      raise Exception(f"path {p} not in repo {top}")
    if rel.startswith(".."):
      raise Exception(f"path {p} not in repo {top}")
    return rel.replace(os.sep, "/")

  def type(self, rel):
    return self.git_output(["git", "cat-file", "-t", f"HEAD:{rel}"],
                           cwd=self._top()).strip()

  def isfile(self, rel):
    return self.type(rel) == "blob"

  def _allowed(self, rel):
    try:
      ok = self.checkout_filter(rel)
    except Exception as e:
      perr(f"checkout_filter error on {rel}: {e}")
      return False
    if not ok:
      pout(f"skip {rel} (filtered)")
    return ok

  def ensurepresent(self, sha):
    toplevel = self._top()
    def subgitcmd(cmd):
      with subprocess.Popen(cmd,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE,
                            cwd=toplevel) as p:
        _, err = p.communicate()
        if p.returncode != 0:
          raise Exception(err.strip() or f"cmd failed: {cmd}")

    cmd = ["git", "cat-file", "-e", sha]
    subgitcmd(cmd)
    cmd = ["git", "cat-file", "-s", sha]
    subgitcmd(cmd)

  def getsparselist(self):
    try:
      out = self.git_output(["git", "sparse-checkout", "list"], cwd=self._top())
    except Exception:
      return []
    return [l.strip() for l in out.splitlines() if l.strip()]

  def iscovered(self, rel, sparse, toaddsparse):
    if "." in sparse:
      return True
    if "/" not in rel:
      if self.isfile(rel):
        return True
      return rel in sparse
    for e in sparse:
      if rel == e or rel.startswith(e.rstrip("/") + "/"):
        return True
    for e in toaddsparse:
      if rel == e or rel.startswith(e.rstrip("/") + "/"):
        return True
    return False
