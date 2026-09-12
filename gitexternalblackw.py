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

class InitCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw init <path> [--force]"

  def run(self, argv):
    path, force = self.parseargs(argv)
    if os.path.exists(path) and not os.path.isdir(path):
      raise Exception(f"{path} is not a directory")
    if not os.path.exists(path):
      os.makedirs(path)
    if self.isrepo(path):
      if not force:
        raise Exception(f"{path} is already a git repo, use --force to refresh configs")
      self.applyconfig(path)
    else:
      self.initrepo(path)

  def parseargs(self, argv):
    args = argv[2:]
    force = False
    path = None
    for a in args:
      if a == "--force":
        force = True
      elif a in ("-h", "--help"):
        raise Exception(f"{self.usage}")
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        path = a
    if not path:
      raise Exception(f"{self.usage}")
    return path, force

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
    run(['git', 'config', 'fetch.writecommitgraph', 'true'], cwd=path)
    run(['git', 'config', 'remote.origin.promisor', 'true'], cwd=path)
    run(['git', 'config', 'remote.origin.partialclonefilter', 'combine:blob:none+tree:0'], cwd=path)
    run(['git', 'sparse-checkout', 'init', '--cone', '--sparse-index'], cwd=path)
    run(['git', 'commit-graph', 'write', '--reachable', '--changed-paths'], cwd=path)

  def applyconfig(self, path):
    self.blackw.run_cmd(['git', 'remote', 'set-url', 'origin', f"blackw::x.x.x.x:port"], cwd=path)
    self.applyconfig0(path)
    pout(f"refreshed configs in {path} (repo untouched: no fetch, no HEAD change)")

  def initrepo(self, path):
    run = self.blackw.run_cmd
    run(['git', 'init', '.'], cwd=path)
    run(['git', 'remote', 'add', 'origin', f"blackw::{path}"], cwd=path)
    self.applyconfig0(path)
    run(['git', 'fetch', '--depth=1', '--update-shallow', 'origin'], cwd=path)
    self.inithead(path)

  def remotedefaultbranch(self, path):
    self.blackw.git_output(["git", "remote", "set-head", "origin", "-a"], cwd=path)
    out = self.blackw.git_output(["git", "symbolic-ref", "refs/remotes/origin/HEAD"],
                                 cwd=path).strip()
    if not out.startswith("refs/remotes/origin/"):
      raise Exception(f"remote branch name broken {out}")
    return out[len("refs/remotes/origin/"):]

  def inithead(self, path):
    branch = self.remotedefaultbranch(path)
    run = self.blackw.run_cmd
    run(["git", "update-ref", f"refs/heads/{branch}", "FETCH_HEAD"], cwd=path)
    run(["git", "symbolic-ref", "HEAD", f"refs/heads/{branch}"], cwd=path)
    run(["git", "config", f"branch.{branch}.remote", "origin"], cwd=path)
    run(["git", "config", f"branch.{branch}.merge", f"refs/heads/{branch}"], cwd=path)
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

  def sparsify_all(self, toplevel):
    self.run_cmd(["git", "read-tree", "HEAD"], cwd=toplevel)
    out = self.git_output(["git", "ls-files", "-z"], cwd=toplevel)
    files = [f for f in out.split("\x00") if f]
    if not files:
      return
    self.run_cmd(["git", "update-index", "--skip-worktree", "--"] + files,
                 cwd=toplevel)
    pout("sparsified all (empty worktree, skip-worktree, status clean)")

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
