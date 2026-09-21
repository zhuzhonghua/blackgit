import subprocess
import sys
import os

# Cared-file set (one repo-relative blob path per line, sorted, unique).
ADD_FILE = "blackw-add.tsv"

HELP = """usage: git black <command> [<args>]

blackgit is a sparse/promisor git client for monorepos: only the
cared file set is materialized, everything else is fetched on demand.

commands:
  clone <url> [<dir>]      clone a repo (blob:none sparse checkout)
  ls [<ref>|<path>|<branch>:<path>]
                           list files/dirs in the tree
  branch                   list branches
  follow <path>...         add files/dirs to the cared set
  follow -r|--recursive <dir>...
                           add a whole subtree to the cared set
  follow -d <path>...      remove files/dirs from the cared set
  follow -l|--list         list cared files
  update                   fast-forward update to origin (commits only)
  lock <path>              lock a file
  lock -d <path>           unlock a file
  locks                    list locked files
  help                     show this help

any other command is passed through to stock git (push, status, log, ...).

run `git black agent` to enter the AI Agent mode
(natural-language git assistant)."""

def pp(line, target):
  target.write(line)
  if not line.endswith('\n'):
    target.write('\n')
  target.flush()

def pout(line):
  pp(line, sys.stdout)

def perr(line):
  pp(line, sys.stderr)

class CloneCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git black clone <url> [<dir>]"

  def run(self, argv):
    url, dest = self.parseargs(argv)
    self.checkurl(url)
    dest = dest or self.defaultdir(url)
    pout(f"clone url={url} dest={dest}")
    self.createdest(dest)
    run = self.blackw.run_cmd
    run(['git', 'init', '.'], cwd=dest)
    run(['git', 'remote', 'add', 'origin', url], cwd=dest)
    # Auth is left entirely to git's standard HTTP layer (credential helper,
    # http.extraHeader, system keychain, ...). blackw never parses user info
    # out of the URL, so the token never ends up embedded in remote.origin.url.
    self.partialclone(dest)
    branch = self.defaultbranch(dest)
    self.fetch(dest)
    self.setheadorigin(dest)
    self.checkout(dest, branch)
    self.sparsify(dest)
    self.mark_server(dest)
    pout(f"clone done: {dest} on branch {branch}")

  def parseargs(self, argv):
    import argparse
    p = argparse.ArgumentParser(
        prog="git black clone",
        usage="git black clone <url> [<dir>]")
    p.add_argument("url")
    p.add_argument("dest", nargs="?")
    ns = p.parse_args(argv[2:])
    return ns.url, ns.dest

  def checkurl(self, url):
    if not url.startswith("https://") and not url.startswith("http://"):
      raise Exception(f"only https/http URL is supported: {url}\n{self.usage}")
    if not url.endswith(".git"):
      raise Exception(f"only http(s)://xxxx/xxx.git URL is supported: {url}\n{self.usage}")

  def defaultdir(self, url):
    name = url.rstrip("/").rsplit("/", 1)[-1]
    return name[:-len(".git")]

  def createdest(self, dest):
    if os.path.exists(dest):
      if not os.path.isdir(dest):
        raise Exception(f"destination '{dest}' is not a directory")
      if any(os.scandir(dest)):
        raise Exception(f"destination '{dest}' already exists and is not empty")
    else:
      os.makedirs(dest, exist_ok=True)

  def partialclone(self, dest):
    # blob:none: trees/commits come with the fetch, blobs arrive on demand.
    run = self.blackw.run_cmd
    run(['git', 'config', 'remote.origin.promisor', 'true'], cwd=dest)
    run(['git', 'config', 'extensions.partialclone', 'origin'], cwd=dest)
    run(['git', 'config', 'remote.origin.partialclonefilter', 'blob:none'],
        cwd=dest)

  def mark_server(self, dest):
    """Probe GET {origin}/authz to tell a blackgit server from a third-party
    git server (GitHub/GitLab/...), and record the result in blackgit.server.
    A blackgit server answers /authz even without credentials with a
    {"server":"blackgit","read":[...]} payload, so the probe is anonymous and
    detection never depends on credentials. Anything else — 404/401/network
    error/non-JSON — is a third-party server. `git black ls` probes /authz
    only when blackgit.server=true; clones from third-party servers are
    standalone and never touch the permission API."""
    import urllib.parse, urllib.request, json
    try:
      url = self.blackw.git_output(
          ["git", "remote", "get-url", "origin"], cwd=dest,
          silent=True).strip()
    except Exception:
      url = ""
    blackgit = False
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme in ("http", "https"):
      req = urllib.request.Request(url.rstrip("/") + "/authz")
      try:
        with urllib.request.urlopen(req, timeout=10) as resp:
          data = json.loads(resp.read())
        blackgit = (isinstance(data, dict)
                    and isinstance(data.get("read"), list))
      except Exception:
        blackgit = False
    self.blackw.run_cmd(
        ["git", "config", "blackgit.server", "true" if blackgit else "false"],
        cwd=dest)
    pout(f"clone: origin is {'a blackgit server' if blackgit else 'a third-party git server'} "
         f"-> blackgit.server={str(blackgit).lower()}")

  def fetch(self, dest):
    self.blackw.run_cmd(['git', 'fetch', '--depth=1', '--filter=blob:none',
                         'origin'], cwd=dest)

  def sparsify(self, dest):
    run = self.blackw.run_cmd
    run(['git', 'read-tree', 'HEAD'], cwd=dest)
    run(['git', 'sparse-checkout', 'set', '--no-cone',
         '!/*', '!/*/*'], cwd=dest)
    pout("clone: sparse checkout on (empty worktree, no blobs fetched)")

  def defaultbranch(self, dest):
    g = self.blackw.git_output
    out = g(['git', 'ls-remote', '--symref', 'origin', 'HEAD'],
            cwd=dest).strip()
    for l in out.splitlines():
      l = l.strip()
      if l.startswith("ref:"):
        name = l.split("ref:", 1)[1].split("\t", 1)[0].strip()
        if name.startswith("refs/heads/"):
          return name[len("refs/heads/"):]
    heads = g(['git', 'ls-remote', '--heads', 'origin'], cwd=dest).strip().splitlines()
    names = [l.split("\t")[1] for l in heads if "\t" in l]
    if not names:
      raise Exception("no heads on remote origin")
    branch = names[0][len("refs/heads/"):]
    if len(names) > 1:
      pout(f"clone: no symref HEAD, picking {branch}")
    return branch

  def setheadorigin(self, dest):
    try:
      self.blackw.git_output(['git', 'remote', 'set-head', 'origin', '-a'],
                             cwd=dest)
    except Exception as e:
      pout(f"clone: origin HEAD not set ({e})")

  def checkout(self, dest, branch):
    run = self.blackw.run_cmd
    self.blackw.git_output(['git', 'rev-parse', '--verify',
                            f'refs/remotes/origin/{branch}'], cwd=dest)
    # Point HEAD at the remote tip WITHOUT materializing the worktree:
    # a real `git checkout` would try to read every blob and turn the
    # blob:none clone into a full download.
    run(['git', 'update-ref', f'refs/heads/{branch}',
         f'refs/remotes/origin/{branch}'], cwd=dest)
    run(['git', 'symbolic-ref', 'HEAD', f'refs/heads/{branch}'], cwd=dest)

class BranchCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git black branch"

  def run(self, argv):
    if len(argv) > 2:
      raise Exception(f"{self.usage}")
    bw = self.blackw
    toplevel = bw._top()
    heads = bw.git_output(['git', 'for-each-ref', '--format=%(refname)',
                           'refs/heads'], cwd=toplevel).strip().splitlines()
    remotes = bw.git_output(['git', 'for-each-ref', '--format=%(refname)',
                             'refs/remotes'], cwd=toplevel).strip().splitlines()
    heads = [h for h in heads if h]
    remotes = [r for r in remotes if r]
    try:
      cur = bw.git_output(['git', 'symbolic-ref', '--short', '-q', 'HEAD'],
                          cwd=toplevel).strip()
    except Exception:
      cur = ""
    lines = []
    for name in heads:
      short = name[len("refs/heads/"):]
      lines.append(f"* {short}" if short == cur else f"  {short}")
    headref = "refs/remotes/origin/HEAD"
    if headref in remotes:
      remotes.remove(headref)
      try:
        target = bw.git_output(['git', 'symbolic-ref', headref],
                               cwd=toplevel).strip()
        lines.append(f"  {headref[len('refs/'):]} -> "
                     f"{target[len('refs/remotes/'):]}")
      except Exception:
        pass
    for name in remotes:
      lines.append(f"  {name[len('refs/'):]}")
    if not lines:
      pout("(no branches)")
      return
    for line in lines:
      pout(line)

class LsCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git black ls [<ref> | <path> | <branch>:<path>]"

  def run(self, argv):
    extra = argv[2:]
    if len(extra) > 1:
      raise Exception(f"{self.usage}")
    if extra and extra[0] in ("-h", "--help"):
      raise Exception(f"{self.usage}")
    arg = extra[0] if extra else None
    bw = self.blackw
    toplevel = bw._top()
    allowed = self._fetch_allowed(toplevel)
    if arg is None:
      out = bw.git_output(['git', 'ls-tree', 'HEAD'], cwd=toplevel)
      self._print_filtered(out, allowed, '')
      return
    if ":" in arg:
      ref, rest = arg.split(":", 1)
      ref = ref or "HEAD"
      self._checkref(toplevel, ref)
      target = f"{ref}:{rest}" if rest else ref
      if rest:
        otype = bw.git_output(['git', 'cat-file', '-t', target],
                              cwd=toplevel).strip()
        if otype != "tree":
          raise Exception(f"not a dir {arg}\n{self.usage}")
      out = bw.git_output(['git', 'ls-tree', target], cwd=toplevel)
      self._print_filtered(out, allowed, rest)
      return
    try:
      self._checkref(toplevel, arg)
      out = bw.git_output(['git', 'ls-tree', arg], cwd=toplevel)
      self._print_filtered(out, allowed, '')
      return
    except Exception:
      pass
    # Paths are always relative to the repo root, not the cwd.
    rel = arg.strip("/").strip(".")
    if rel:
      otype = bw.git_output(['git', 'cat-file', '-t', f'HEAD:{rel}'],
                            cwd=toplevel).strip()
      if otype != "tree":
        raise Exception(f"not a dir {rel}\n{self.usage}")
      out = bw.git_output(['git', 'ls-tree', f'HEAD:{rel}'], cwd=toplevel)
      self._print_filtered(out, allowed, rel)
    else:
      out = bw.git_output(['git', 'ls-tree', 'HEAD'], cwd=toplevel)
      self._print_filtered(out, allowed, '')

  def _fetch_allowed(self, toplevel):
    """GET /authz -> list of readable path prefixes, or None (= no filter).

    `git black clone` records blackgit.server=true when the origin is a
    blackgit server, false for a third-party git server (GitHub/GitLab/...).
    Only true enables the permission check here; false or absent means the
    repo is standalone and /authz is never probed — no request, no warning,
    no dependency on the server. With true, the /authz response itself is
    the final discriminator: only the {"read": [...]} payload enables
    filtering; anything else (404/401/network error/non-JSON) means no
    filter, silently."""
    try:
      marker = self.blackw.git_output(
          ["git", "config", "--local", "--get", "blackgit.server"],
          cwd=toplevel, silent=True).strip()
    except Exception:
      return None
    if marker != "true":
      return None  # no blackgit marker -> standalone, no authz
    import urllib.parse
    try:
      url = self.blackw.git_output(
          ["git", "remote", "get-url", "origin"], cwd=toplevel,
          silent=True).strip()
    except Exception:
      return None  # no origin -> standalone, nothing to filter against
    if urllib.parse.urlparse(url).scheme not in ("http", "https"):
      return None  # non-http origin -> not a git-over-HTTP server, no authz
    import urllib.request, json
    parsed = urllib.parse.urlparse(url)
    netloc = parsed.netloc
    if "@" in netloc:
      netloc = netloc.split("@", 1)[1]
    inp = (f"protocol={parsed.scheme}\nhost={netloc}\n\n")
    try:
      out = self.blackw.git_output(["git", "credential", "fill"],
                                    cwd=toplevel, input=inp, silent=True)
    except Exception:
      return None
    creds = {}
    for line in out.splitlines():
      if "=" in line:
        k, v = line.split("=", 1)
        creds[k] = v
    import base64
    raw = creds.get("username", "") + ":" + creds.get("password", "")
    auth = "Basic " + base64.b64encode(raw.encode()).decode()
    api = url.rstrip("/") + "/authz"
    req = urllib.request.Request(api, headers={"Authorization": auth})
    try:
      with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    except Exception:
      return None  # not a blackgit server (or unreachable): no filter
    read = data.get("read") if isinstance(data, dict) else None
    if not isinstance(read, list):
      return None  # not a blackgit authz payload: no filter
    if "**" in read:
      return None  # everything readable
    return read

  def _visible(self, path, allowed):
    """An entry is visible if it is itself under an allowed prefix, or its
    subtree contains an allowed prefix (so the user can navigate down)."""
    if allowed is None:
      return True
    norm = path.strip("/")
    for prefix in allowed:
      p = prefix.strip("/")
      if norm == p:
        return True
      if norm.startswith(p + "/"):
        return True  # entry under allowed dir
      if p.startswith(norm + "/"):
        return True  # entry is a parent dir of an allowed path
    return False

  def _print_filtered(self, ls_tree_output, allowed, prefix=""):
    for line in ls_tree_output.splitlines():
      if "\t" not in line:
        continue
      meta, _, name = line.partition("\t")
      parts = meta.split()
      if len(parts) < 3:
        continue
      full = (prefix + "/" + name).strip("/") if prefix else name
      if self._visible(full, allowed):
        print(line)

  def _checkref(self, toplevel, ref):
    self.blackw.git_output(['git', 'rev-parse', '--verify', '--quiet', ref],
                           cwd=toplevel)

class FollowCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = ("usage: git black follow <path> [<path>...]\n"
                  "       git black follow -r|--recursive <dir> [<dir>...]\n"
                  "       git black follow -d <path> [<path>...]\n"
                  "       git black follow -l | --list")

  def run(self, argv):
    args = argv[2:]
    if not args:
      raise Exception(f"{self.usage}")
    if args[0] in ("-l", "--list"):
      if len(args) != 1:
        raise Exception(f"{self.usage}")
      self.listpaths()
      return
    recursive = False
    delete = False
    paths = []
    for a in args:
      if a in ("-d", "--delete"):
        delete = True
      elif a in ("-r", "--recursive"):
        recursive = True
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        paths.append(a)
    if delete:
      if not paths:
        # -d with no paths: clear the entire follow list.
        self.deleteall()
        return
      self.deletepaths(paths)
      return
    bw = self.blackw
    toplevel = bw._top()
    kept = bw.read_add_set(toplevel)
    added = []
    for p in paths:
      rel = bw.normalizerel(p)
      if rel == ".":
        raise Exception(f"not a file {p}\n{self.usage}")
      if "\n" in rel or "\t" in rel:
        raise Exception(f"path with newline/tab is not supported: {p}")
      entry = bw.ls_entry(toplevel, "HEAD", rel)
      if entry is None:
        raise Exception(f"no such path in HEAD: {rel}\n"
                        f"run 'git black ls' to see the tree")
      mode, typ, sha = entry
      if typ == "blob":
        if rel not in kept:
          kept.add(rel)
          added.append(rel)
      elif typ == "tree":
        # kept stores include paths only; exclusion rules are computed
        # dynamically in set_sparse.
        dir_rule = rel.rstrip("/") + "/"
        if dir_rule not in kept:
          kept.add(dir_rule)
          added.append(dir_rule)
      else:
        raise Exception(f"follow only supports files/directories, {rel} is a {typ}\n"
                        f"{self.usage}")
    bw.set_sparse(toplevel, kept)
    bw.write_add_set(toplevel, kept)
    if added:
      pout(f"follow: +{', '.join(added)} (now caring about {len(kept)} file(s), "
           f"view applied)")
    else:
      pout(f"follow: already caring about all of: {', '.join(paths)}")

  def deleteall(self):
    bw = self.blackw
    toplevel = bw._top()
    kept = bw.read_add_set(toplevel)
    if not kept:
      pout("follow: nothing to clear (already empty)")
      return
    dirty = bw.git_output(["git", "status", "--porcelain"],
                          cwd=toplevel).strip()
    if dirty:
      raise Exception(f"follow -d: worktree has local changes, resolve them "
                      f"first:\n{dirty}")
    bw.set_sparse(toplevel, set())
    bw.write_add_set(toplevel, set())
    pout(f"follow: cleared {len(kept)} path(s), sparse-checkout "
         f"'!/* !/*/*' re-armed, empty worktree")

  def deletepaths(self, paths):
    bw = self.blackw
    toplevel = bw._top()
    kept = bw.read_add_set(toplevel)
    rels = []
    for p in paths:
      rel = bw.normalizerel(p)
      if rel == ".":
        raise Exception(f"not a file {p}\n{self.usage}")
      rels.append(rel)
    # Expand a directory argument to every cared file under it (prefix match),
    # so `add -d somedir` removes everything that `add somedir [--recursive]`
    # pulled in. A plain file path maps to itself.
    to_remove = set()
    for rel in rels:
      entry = bw.ls_entry(toplevel, "HEAD", rel)
      if entry is not None and entry[1] == "tree":
        prefix = rel + "/"
        expanded = {f for f in kept if f.startswith(prefix)}
        if not expanded:
          raise Exception(f"directory {rel} has no cared files\n"
                          f"run 'git black follow -l' to list")
        to_remove.update(expanded)
      else:
        to_remove.add(rel)
    missing = [r for r in to_remove if r not in kept]
    if missing:
      raise Exception(f"not in the cared set: {', '.join(sorted(missing))}\n"
                      f"run 'git black follow -l' to list")
    if not kept:
      raise Exception(f"nothing added yet\n{self.usage}")
    for r in to_remove:
      kept.discard(r)
    if not kept:
      # Nothing cared anymore: re-arm the full-exclusion sparse rule so a
      # later operation can never materialize root files.
      dirty = bw.git_output(["git", "status", "--porcelain"],
                            cwd=toplevel).strip()
      if dirty:
        raise Exception(f"add -d: worktree has local changes, resolve them "
                        f"first (sparse-checkout cannot clean it):\n{dirty}")
      bw.set_sparse(toplevel, set())
      bw.write_add_set(toplevel, kept)
      pout(f"add: -{', '.join(rels)} (nothing cared anymore, "
           f"sparse-checkout '!/* !/*/*' re-armed, empty worktree)")
    else:
      bw.set_sparse(toplevel, kept)
      bw.write_add_set(toplevel, kept)
      pout(f"add: -{', '.join(rels)} (now caring about {len(kept)} file(s), "
           f"view applied)")

  def listpaths(self):
    bw = self.blackw
    toplevel = bw._top()
    kept = bw.read_add_set(toplevel)
    if not kept:
      pout("(nothing added)")
      return
    for rel in sorted(kept):
      pout(rel)
    pout(f"({len(kept)} file(s))")

class UpdateCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git black update"

  def run(self, argv):
    if len(argv) > 2:
      raise Exception(f"{self.usage}")
    bw = self.blackw
    top = bw._top()
    branch = self.current_branch(bw, top)
    # 1. Fetch only the missing commit objects: filter=tree:0 brings commits
    #    and nothing else. After a long gap a plain `git pull` would walk the
    #    whole tree history and materialize blobs; this keeps the pull cheap.
    #    Any tree/blob needed later is lazy-fetched on demand.
    bw.run_cmd(["git", "fetch", "--filter=tree:0", "--no-tags", "origin"],
               cwd=top)
    local = bw.git_output(["git", "rev-parse", "HEAD"], cwd=top).strip()
    remote = bw.git_output(["git", "rev-parse", "--verify",
                            f"origin/{branch}"], cwd=top).strip()
    # 2. Stash any uncommitted changes (deleted files are auto-restored anyway).
    dirty = bw.git_output(["git", "status", "--porcelain"], cwd=top).strip()
    stashed = False
    if dirty:
      try:
        bw.run_cmd(["git", "stash", "push", "-m", "blackgit-update-auto"],
                   cwd=top)
        stashed = True
      except Exception:
        pass
    if local == remote:
      # Already up to date. Restore any deleted files, then put user changes back.
      if stashed:
        bw.run_cmd(["git", "checkout", "-f", "HEAD", "--", "."], cwd=top)
        try:
          bw.run_cmd(["git", "stash", "pop"], cwd=top)
        except Exception:
          raise Exception(
              "update: stash pop conflicts — resolve them manually, then run "
              "'git stash drop' when done")
      else:
        bw.run_cmd(["git", "checkout", "-f", "HEAD", "--", "."], cwd=top)
      pout(f"update: already up to date ({local[:8]}), worktree restored")
      return
    # 2. Stash any uncommitted changes (deleted files are auto-restored anyway).
    dirty = bw.git_output(["git", "status", "--porcelain"], cwd=top).strip()
    stashed = False
    if dirty:
      try:
        bw.run_cmd(["git", "stash", "push", "-m", "blackgit-update-auto"],
                   cwd=top)
        stashed = True
      except Exception:
        pass
    # 3. Fast-forward or rebase.
    mb = bw.git_output(["git", "merge-base", local, remote], cwd=top).strip()
    if mb == local:
      # Fast-forward: move the branch pointer directly.
      bw.run_cmd(["git", "update-ref", f"refs/heads/{branch}", remote], cwd=top)
      action = "fast-forward"
    else:
      # Diverged: try rebase. If it conflicts, bail out and let the user resolve.
      try:
        bw.run_cmd(["git", "rebase", remote], cwd=top)
        action = "rebase"
      except Exception:
        # Roll back: abort rebase and restore stashed changes so the user's
        # work is never lost.
        bw.run_cmd(["git", "rebase", "--abort"], cwd=top)
        if stashed:
          try:
            bw.run_cmd(["git", "stash", "pop"], cwd=top)
          except Exception:
            pass
        raise Exception(
            "update: rebase conflicts — your local changes have been "
            "restored. Resolve conflicts manually and re-run 'git black update'")
    # Restore stashed changes if any.
    if stashed:
      try:
        bw.run_cmd(["git", "stash", "pop"], cwd=top)
      except Exception:
        raise Exception(
            "update: stash pop conflicts — resolve them manually, then run "
            "'git stash drop' when done")
    bw.run_cmd(["git", "read-tree", "HEAD"], cwd=top)
    paths = bw.read_add_set(top)
    bw.set_sparse(top, paths)
    bw.run_cmd(["git", "checkout", "-f", "HEAD", "--", "."], cwd=top)
    pout(f"update: {branch} {local[:8]} -> {remote[:8]} ({action}; "
         f"commits only; trees/blobs on demand)")

  def current_branch(self, bw, top):
    try:
      branch = bw.git_output(["git", "symbolic-ref", "--short", "-q", "HEAD"],
                             cwd=top).strip()
    except Exception:
      branch = ""
    if not branch:
      raise Exception(f"update: HEAD is detached; run on a branch\n{self.usage}")
    return branch

class LockCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = ("usage: git black lock <path>\n"
                  "       git black lock -d <path>\n"
                  "       git black locks")

  def run(self, argv):
    args = argv[2:]
    if not args:
      raise Exception(f"{self.usage}")
    if args[0] == "locks" or args[0] == "-l" or args[0] == "--list":
      self.list_locks()
      return
    delete = False
    paths = []
    for a in args:
      if a in ("-d", "--delete", "--unlock"):
        delete = True
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        paths.append(a)
    if not paths:
      raise Exception(f"{self.usage}")
    if delete:
      for p in paths:
        self.unlock(p)
    else:
      for p in paths:
        self.lock(p)

  def _server_url(self, top, repo_segment):
    """origin URL like http://host:port/demo.git -> http://host:port/demo.git/lock"""
    url = self.blackw.git_output(["git", "remote", "get-url", "origin"],
                                  cwd=top).strip()
    return url.rstrip("/") + "/lock?path=" + repo_segment

  def _auth(self, top, origin_url):
    """Ask git credential for username:password for this origin."""
    import urllib.parse
    parsed = urllib.parse.urlparse(origin_url)
    host = parsed.hostname
    # git credential uses host[:port]
    netloc = parsed.netloc
    # strip userinfo if embedded
    if "@" in netloc:
      netloc = netloc.split("@", 1)[1]
    inp = f"protocol={parsed.scheme}\nhost={netloc}\n\n"
    out = self.blackw.git_output(["git", "credential", "fill"],
                                  cwd=top, input=inp)
    creds = {}
    for line in out.splitlines():
      if "=" in line:
        k, v = line.split("=", 1)
        creds[k] = v
    import base64
    raw = creds.get("username", "") + ":" + creds.get("password", "")
    return "Basic " + base64.b64encode(raw.encode()).decode()

  def lock(self, path):
    bw = self.blackw
    top = bw._top()
    rel = bw.normalizerel(path)
    url = bw.git_output(["git", "remote", "get-url", "origin"], cwd=top).strip()
    api = url.rstrip("/") + "/lock?path=" + rel
    auth = self._auth(top, url)
    import urllib.request, json
    req = urllib.request.Request(api, method="POST", headers={"Authorization": auth})
    try:
      with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        pout(f"lock: {data['path']} locked by {data['user']}")
    except urllib.error.HTTPError as e:
      msg = e.read().decode()
      raise Exception(f"lock: {e.code} {msg}")

  def unlock(self, path):
    bw = self.blackw
    top = bw._top()
    rel = bw.normalizerel(path)
    url = bw.git_output(["git", "remote", "get-url", "origin"], cwd=top).strip()
    api = url.rstrip("/") + "/lock?path=" + rel
    auth = self._auth(top, url)
    import urllib.request, json
    req = urllib.request.Request(api, method="DELETE", headers={"Authorization": auth})
    try:
      with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        pout(f"lock: {data['path']} unlocked")
    except urllib.error.HTTPError as e:
      msg = e.read().decode()
      raise Exception(f"lock: {e.code} {msg}")

  def list_locks(self):
    bw = self.blackw
    top = bw._top()
    url = bw.git_output(["git", "remote", "get-url", "origin"], cwd=top).strip()
    api = url.rstrip("/") + "/lock"
    auth = self._auth(top, url)
    import urllib.request, json
    req = urllib.request.Request(api, method="GET", headers={"Authorization": auth})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    if not data:
      pout("(no locks)")
      return
    for path, info in sorted(data.items()):
        pout(f"  {path}  locked by {info['user']} since {info.get('since','?')}")
    pout(f"({len(data)} lock(s))")

class BlackGitCli:
  def __init__(self):
    self.toplevel = None

  def run(self, argv):
    pout(f"black run {argv}")
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd in ("-h", "--help", "help"):
      self.showhelp()
      return
    if cmd == "agent":
      # Enter the natural-language agent REPL. Imported lazily so ordinary
      # commands never load the agent (or its openai dependency).
      import blackgitagent
      sys.exit(blackgitagent.main())
    if cmd == "ls":
      LsCommand(self).run(argv)
    elif cmd == "branch":
      BranchCommand(self).run(argv)
    elif cmd == "clone":
      CloneCommand(self).run(argv)
    elif cmd == "follow":
      FollowCommand(self).run(argv)
    elif cmd == "update":
      UpdateCommand(self).run(argv)
    elif cmd == "lock":
      LockCommand(self).run(argv)
    elif cmd == "locks":
      LockCommand(self).list_locks()
    else:
      # Everything blackw does not override (push, status, log, ...) is handed
      # straight to stock git as `git <args...>`. execvp replaces this process,
      # so stdin/stdout/stderr and the exit code pass through unchanged —
      # interactive flows (password prompts, merge editor, ...) keep working
      # exactly as with git itself.
      os.execvp("git", ["git"] + argv[1:])

  def showhelp(self):
    pout(HELP)

  def _top(self):
    if self.toplevel is None:
      self.toplevel = self.git_output(["git", "rev-parse", "--show-toplevel"]).strip()
    return self.toplevel

  def gitdir(self, top):
    return self.git_output(["git", "rev-parse", "--absolute-git-dir"],
                           cwd=top).strip()

  def add_file(self, top):
    # The cared-file set lives in the repo's gitdir. Auth is handled by git's
    # standard HTTP layer, so there is no per-user suffix here.
    return os.path.join(self.gitdir(top), ADD_FILE)

  def read_add_set(self, top):
    f = self.add_file(top)
    if not os.path.isfile(f):
      return set()
    out = set()
    with open(f, encoding="utf-8") as fh:
      for line in fh:
        p = line.rstrip("\n")
        if p:
          out.add(p)
    return out

  def write_add_set(self, top, paths):
    f = self.add_file(top)
    tmp = f + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
      for p in sorted(paths):
        fh.write(p + "\n")
    os.replace(tmp, f)

  def set_sparse(self, top, paths):
    """Apply the cared-file view with git's native sparse-checkout: HEAD and
    the index stay real (commits/push/rebase all see the full tree); only the
    cared blobs are materialized in the worktree. Whitelist rules, so files
    added on the server later never leak into the view. paths empty -> exclude
    everything.
    paths is a set of include paths (files or directories ending in '/').
    Exclusion rules for non-recursive directories are computed dynamically."""
    read = self.authz_read_prefixes(top)
    if read is None:
      effective = set(paths)
    else:
      effective = set()
      for p in paths:
        if any(p == r or p.startswith(r + "/") for r in read):
          effective.add(p)
    # Compute exclusion rules: for each non-recursive directory in effective,
    # list its immediate subdirs; exclude those not also in effective.
    excludes = self.compute_excludes(top, effective)
    all_rules = effective | excludes
    if not all_rules:
      rules = ["!/*", "!/*/*"]
    else:
      rules = [p if p.startswith("!") else "/" + p for p in sorted(all_rules)]
    self.run_cmd(["git", "sparse-checkout", "set", "--no-cone"] + rules,
                 cwd=top)

  def compute_excludes(self, top, include_paths):
    """For each non-recursive directory in include_paths, exclude its
    immediate subdirs that are not themselves in include_paths."""
    excludes = set()
    dirs = [p for p in include_paths if p.endswith("/")]
    for d in dirs:
      subdirs = self.list_subdirs(top, "HEAD", d.rstrip("/"))
      for sd in subdirs:
        subpath = d + sd + "/"
        if subpath not in include_paths:
          excludes.add("!" + subpath)
    return excludes

  def authz_read_prefixes(self, top):
    """Readable path prefixes from the blackgit server's /authz API.
    Returns None when no authz filter (standalone or open mode);
    otherwise returns a set of normalized path strings (no leading /, no trailing /)."""
    try:
      read = LsCommand(self).authz_rules(top)
    except Exception:
      return None
    if not read or "**" in read:
      return None
    out = set()
    for r in read:
      r = r.strip("/")
      if r:
        out.add(r)
    return out

  def is_blackgit_server(self, top):
    """True when origin is a blackgit server (has authz layer)."""
    try:
      out = self.git_output(
          ["git", "config", "--local", "--get", "blackgit.server"],
          cwd=top).strip().lower()
      return out == "true"
    except Exception:
      return False

  def list_subdirs(self, top, treeish, dir_rel):
    """Immediate subdir names under dir_rel (no leading path, no trailing /)."""
    cmd = ["git", "ls-tree", "-z", f"{treeish}:{dir_rel}"]
    out = self.git_output(cmd, cwd=top)
    subs = []
    for raw in out.split("\0"):
      if not raw:
        continue
      meta, _, name = raw.partition("\t")
      parts = meta.split()
      if len(parts) == 3 and parts[1] == "tree":
        subs.append(name)
    return subs

  def ls_tree_blobs(self, top, treeish, dir_rel, recursive):
    """All blob paths directly under dir_rel in treeish, repo-relative.
    Without recursive only top-level entries are listed; with recursive the
    whole subtree is walked. Trees themselves are skipped."""
    cmd = ["git", "ls-tree", "-z"]
    if recursive:
        cmd.append("-r")
    # Use treeish:dir_rel colon syntax to list contents *inside* the dir,
    # not the dir entry itself.
    cmd += [f"{treeish}:{dir_rel}"]
    out = self.git_output(cmd, cwd=top)
    blobs = []
    prefix = dir_rel.rstrip("/") + "/"
    for raw in out.split("\0"):
      if not raw:
        continue
      meta, _, name = raw.partition("\t")
      parts = meta.split()
      if len(parts) == 3 and parts[1] == "blob":
        blobs.append(prefix + name)
    return blobs

  def ls_entry(self, top, treeish, rel):
    """(mode, type, sha) of rel in treeish, or None. Paths are literal."""
    try:
      out = self.git_output(["git", "ls-tree", "-z", treeish, "--", rel],
                            cwd=top)
    except Exception:
      return None
    for raw in out.split("\0"):
      if not raw:
        continue
      meta, _, name = raw.partition("\t")
      if name == rel:
        parts = meta.split()
        if len(parts) == 3:
          return (parts[0], parts[1], parts[2])
    return None

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

  def git_output(self, cmd, *arg, **args):
    pout(f"{cmd}")
    inp = args.pop("input", None)
    silent = args.pop("silent", False)
    stdin_arg = subprocess.PIPE if inp is not None else None
    with subprocess.Popen(cmd,
                          stdin=stdin_arg,
                          stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE,
                          text=True,
                          *arg, **args) as p:
      out, err = p.communicate(input=inp)
      if err and not silent:
        perr(err)
      if p.returncode != 0:
        raise Exception(err.strip() or f"cmd failed: {cmd}")
      return out if out else ""

  def run_cmd(self, cmd, *arg, **args):
    out = self.git_output(cmd, *arg, **args)
    if out:
      pout(out)
    return out

def main():
  blackw = BlackGitCli()
  blackw.run(sys.argv)

if __name__ == '__main__':
  main()
