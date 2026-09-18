import subprocess
import sys
import os

# Cared-file set (one repo-relative blob path per line, sorted, unique).
ADD_FILE = "blackw-add.tsv"
# Virtual-commit -> (real commit, path) map, same file blacktrim.py uses.
TRIM_MAP_FILE = "blackw-trim-map.tsv"

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
    self.usage = "usage: git blackw clone <url> [<dir>]"

  def run(self, argv):
    url, dest = self.parseargs(argv)
    self.checkurl(url)
    dest = dest or self.defaultdir(url)
    pout(f"clone url={url} dest={dest}")
    self.createdest(dest)
    run = self.blackw.run_cmd
    run(['git', 'init', '.'], cwd=dest)
    run(['git', 'remote', 'add', 'origin', url], cwd=dest)
    self.partialclone(dest)
    branch = self.defaultbranch(dest)
    self.fetch(dest)
    self.setheadorigin(dest)
    self.checkout(dest, branch)
    self.sparsify(dest)
    pout(f"clone done: {dest} on branch {branch}")

  def parseargs(self, argv):
    import argparse
    p = argparse.ArgumentParser(
        prog="git blackw clone",
        usage="git blackw clone <url> [<dir>]")
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
    self.usage = "usage: git blackw branch"

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
    self.usage = "usage: git blackw ls [<ref> | <path> | <branch>:<path>]"

  def run(self, argv):
    extra = argv[2:]
    if len(extra) > 1:
      raise Exception(f"{self.usage}")
    if extra and extra[0] in ("-h", "--help"):
      raise Exception(f"{self.usage}")
    arg = extra[0] if extra else None
    bw = self.blackw
    toplevel = bw._top()
    if arg is None:
      bw.run_cmd(['git', 'ls-tree', 'HEAD'], cwd=toplevel)
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
      bw.run_cmd(['git', 'ls-tree', target], cwd=toplevel)
      return
    try:
      self._checkref(toplevel, arg)
      bw.run_cmd(['git', 'ls-tree', arg], cwd=toplevel)
      return
    except Exception:
      pass
    rel = bw.normalizerel(arg)
    if rel != ".":
      otype = bw.git_output(['git', 'cat-file', '-t', f'HEAD:{rel}'],
                            cwd=toplevel).strip()
      if otype != "tree":
        raise Exception(f"not a dir {rel}\n{self.usage}")
      bw.run_cmd(['git', 'ls-tree', f'HEAD:{rel}'], cwd=toplevel)
    else:
      bw.run_cmd(['git', 'ls-tree', 'HEAD'], cwd=toplevel)

  def _checkref(self, toplevel, ref):
    self.blackw.git_output(['git', 'rev-parse', '--verify', '--quiet', ref],
                           cwd=toplevel)

class AddCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = ("usage: git blackw add <path> [<path>...]\n"
                  "       git blackw add -d <path> [<path>...]\n"
                  "       git blackw add -l | --list")

  def run(self, argv):
    args = argv[2:]
    if not args:
      raise Exception(f"{self.usage}")
    if args[0] in ("-l", "--list"):
      if len(args) != 1:
        raise Exception(f"{self.usage}")
      self.listpaths()
      return
    delete = False
    paths = []
    for a in args:
      if a in ("-d", "--delete"):
        delete = True
      elif a.startswith("-"):
        raise Exception(f"unknown option {a}\n{self.usage}")
      else:
        paths.append(a)
    if delete:
      if not paths:
        raise Exception(f"{self.usage}")
      self.deletepaths(paths)
      return
    bw = self.blackw
    toplevel = bw._top()
    base = bw.find_real(toplevel)
    kept = bw.read_add_set(toplevel)
    added = []
    for p in paths:
      rel = bw.normalizerel(p)
      if rel == ".":
        raise Exception(f"not a file {p}\n{self.usage}")
      if "\n" in rel or "\t" in rel:
        raise Exception(f"path with newline/tab is not supported: {p}")
      entry = bw.ls_entry(toplevel, base, rel)
      if entry is None:
        raise Exception(f"no such path in {base}: {rel}\n"
                        f"run 'git blackw ls' to see the tree")
      mode, typ, sha = entry
      if typ != "blob":
        raise Exception(f"add only supports files (blob), {rel} is a {typ}\n"
                        f"{self.usage}")
      if rel not in kept:
        kept.add(rel)
        added.append(rel)
    bw.write_add_set(toplevel, kept)
    if added:
      pout(f"add: +{', '.join(added)} (now caring about {len(kept)} file(s))")
    else:
      pout(f"add: already caring about all of: {', '.join(paths)}")

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
    missing = [r for r in rels if r not in kept]
    if missing:
      raise Exception(f"not in the cared set: {', '.join(missing)}\n"
                      f"run 'git blackw add -l' to list")
    if not kept:
      raise Exception(f"nothing added yet\n{self.usage}")
    for r in rels:
      kept.discard(r)
    if not kept:
      # Nothing cared anymore: re-arm the full-exclusion sparse rule so a
      # later checkout can never materialize root files.
      dirty = bw.git_output(["git", "status", "--porcelain"],
                            cwd=toplevel).strip()
      if dirty:
        raise Exception(f"add -d: worktree has local changes, resolve them "
                        f"first (sparse-checkout cannot clean it):\n{dirty}")
      bw.write_add_set(toplevel, kept)
      bw.run_cmd(["git", "sparse-checkout", "set", "--no-cone",
                  "!/*", "!/*/*"], cwd=toplevel)
      pout(f"add: -{', '.join(rels)} (nothing cared anymore, "
           f"sparse-checkout '!/* !/*/*' re-armed, empty worktree)")
    else:
      bw.write_add_set(toplevel, kept)
      pout(f"add: -{', '.join(rels)} (now caring about {len(kept)} file(s))")

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

class CheckoutCommand:
  def __init__(self, blackw):
    self.blackw = blackw
    self.usage = "usage: git blackw checkout"

  def run(self, argv):
    if len(argv) > 2:
      raise Exception(f"{self.usage}")
    bw = self.blackw
    toplevel = bw._top()
    paths = bw.read_add_set(toplevel)
    if not paths:
      raise Exception(f"nothing added yet; run: git blackw add <path>\n{self.usage}")
    base = bw.find_real(toplevel)
    pout(f"checkout: real base={base}, caring about {len(paths)} file(s)")
    entries = []
    for rel in sorted(paths):
      entry = bw.ls_entry(toplevel, base, rel)
      if entry is None:
        raise Exception(f"cared path not found in {base}: {rel}\n"
                        f"remove it from .git/{ADD_FILE} or re-add")
      mode, typ, sha = entry
      if typ != "blob":
        raise Exception(f"cared path is not a file: {rel} is {typ}")
      entries.append((rel, mode, sha))
    dirty = bw.git_output(["git", "status", "--porcelain"],
                          cwd=toplevel).strip()
    if dirty:
      raise Exception(f"checkout: worktree has local changes, "
                      f"commit/stash first:\n{dirty}")
    bw.ensure_blobs(toplevel, [sha for _, _, sha in entries])
    root = bw.git_output(["git", "rev-parse", base + "^{tree}"],
                         cwd=toplevel).strip()
    newroot = bw.trim_tree(toplevel, root, "", set(paths))
    vc = bw.build_virtual(toplevel, base, newroot)
    head = bw.git_output(["git", "rev-parse", "HEAD"], cwd=toplevel).strip()
    if vc == head:
      pout("checkout: view unchanged")
    else:
      bw.record_trim_map(toplevel, vc, base, paths)
      bw.run_cmd(["git", "update-ref", "--no-deref", "HEAD", vc], cwd=toplevel)
      pout(f"checkout: pinned HEAD -> {vc}")
    bw.run_cmd(["git", "read-tree", "HEAD"], cwd=toplevel)
    bw.run_cmd(["git", "checkout-index", "-f", "-a"], cwd=toplevel)
    try:
      bw.run_cmd(["git", "sparse-checkout", "disable"], cwd=toplevel)
    except Exception as e:
      pout(f"checkout: sparse-checkout disable skipped ({e})")
    for rel, _, sha in entries:
      pout(f"released {rel} {sha}")
    pout(f"checkout done: {len(entries)} file(s) in worktree, tree={newroot}")

class BlackGitCli:
  def __init__(self):
    self.toplevel = None

  def run(self, argv):
    pout(f"blackw run {argv}")
    if argv[1] == "ls":
      LsCommand(self).run(argv)
    elif argv[1] == "branch":
      BranchCommand(self).run(argv)
    elif argv[1] == "clone":
      CloneCommand(self).run(argv)
    elif argv[1] == "add":
      AddCommand(self).run(argv)
    elif argv[1] == "checkout":
      CheckoutCommand(self).run(argv)
    else:
      raise Exception(f"unsupport operation {argv[1]}")

  def _top(self):
    if self.toplevel is None:
      self.toplevel = self.git_output(["git", "rev-parse", "--show-toplevel"]).strip()
    return self.toplevel

  def gitdir(self, top):
    return self.git_output(["git", "rev-parse", "--absolute-git-dir"],
                           cwd=top).strip()

  def add_file(self, top):
    return os.path.join(self.gitdir(top), ADD_FILE)

  def trim_map_file(self, top):
    return os.path.join(self.gitdir(top), TRIM_MAP_FILE)

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

  def find_real(self, top):
    """Real commit behind the current HEAD: HEAD itself unless it is a
    recorded virtual commit (vc -> real in the trim map)."""
    head = self.git_output(["git", "rev-parse", "HEAD"], cwd=top).strip()
    f = self.trim_map_file(top)
    if os.path.isfile(f):
      with open(f, encoding="utf-8") as fh:
        for line in fh:
          parts = line.rstrip("\n").split("\t")
          if len(parts) >= 2 and parts[0] == head:
            return parts[1]
    return head

  def record_trim_map(self, top, vc, real, paths):
    """Append vc -> (real, path) lines (one per path), dedup, rewrite."""
    f = self.trim_map_file(top)
    existing = set()
    if os.path.isfile(f):
      with open(f, encoding="utf-8") as fh:
        for line in fh:
          line = line.rstrip("\n")
          if line:
            existing.add(line)
    for p in sorted(paths):
      existing.add(f"{vc}\t{real}\t{p}")
    tmp = f + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
      for line in sorted(existing):
        fh.write(line + "\n")
    os.replace(tmp, f)

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

  def git_bin(self, cmd, *arg, data=None, **args):
    with subprocess.Popen(cmd,
                          stdin=subprocess.PIPE if data is not None else None,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          *arg, **args) as p:
      out, err = p.communicate(data)
      if err:
        perr(err.decode(errors="replace").strip())
      if p.returncode != 0:
        raise Exception(err.decode(errors="replace").strip() or f"cmd failed: {cmd}")
      return out

  def ls_tree_z(self, top, tree):
    out = self.git_bin(["git", "ls-tree", "-z", tree], cwd=top)
    entries = []
    for raw in out.split(b"\0"):
      if not raw:
        continue
      meta, _, name = raw.partition(b"\t")
      parts = meta.split(b" ", 2)
      if len(parts) != 3:
        continue
      entries.append((parts[0].decode(), parts[1].decode(),
                      parts[2].decode(), name.decode("utf-8", "replace")))
    return entries

  def mktree(self, top, entries):
    payload = b"".join(("%s %s %s\t%s" % (m, t, s, n)).encode("utf-8") + b"\0"
                       for (m, t, s, n) in entries)
    out = self.git_bin(["git", "mktree", "-z"], cwd=top, data=payload)
    return out.decode().strip()

  def trim_tree(self, top, tree, prefix, kept):
    """Deterministic trim: keep only the kept blobs (and the directory chain
    that leads to them); drop everything else. Returns None when nothing
    survives (empty dirs are not created)."""
    out = []
    for (mode, typ, sha, name) in self.ls_tree_z(top, tree):
      child = (prefix + "/" + name) if prefix else name
      if typ == "blob":
        if child in kept:
          out.append((mode, "blob", sha, name))
      elif typ == "tree":
        new = self.trim_tree(top, sha, child, kept)
        if new:
          out.append(("040000", "tree", new, name))
    if not out:
      return None
    return self.mktree(top, out)

  def _object_present(self, top, sha):
    env = dict(os.environ)
    env["GIT_NO_LAZY_FETCH"] = "1"
    p = subprocess.run(["git", "-C", top, "cat-file", "-e", sha],
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                       env=env)
    return p.returncode == 0

  def _commit_parents(self, top, sha):
    raw = self.git_bin(["git", "cat-file", "commit", sha], cwd=top)
    idx = raw.find(b"\n\n")
    if idx < 0:
      return None
    parents = []
    for h in raw[:idx].split(b"\n"):
      if h.startswith(b"parent "):
        parents.append(h[7:].decode())
    return parents

  def deepen_commits(self, top, shas):
    """Extend the shallow boundary forward by one layer: fetch only the
    missing parent *commit* objects (filter=tree:0 -> no trees, no blobs),
    then rewrite .git/shallow so git can walk the fetched commits and stop
    at the new boundary."""
    uniq = list(dict.fromkeys(shas))
    pout(f"checkout: deepening history, fetching commits {uniq}")
    self.run_cmd(["git", "fetch", "--no-tags", "--filter=tree:0", "--depth=1",
                  "origin"] + uniq, cwd=top)
    self._refresh_shallow(top, newly_fetched=uniq)

  def _refresh_shallow(self, top, newly_fetched=()):
    """Boundary bookkeeping: commits whose parents are all present locally
    no longer need to be shallow boundaries (drop them); fetched commits
    whose parents are still missing become the new boundaries (keep/add)."""
    f = os.path.join(self.gitdir(top), "shallow")
    entries = []
    if os.path.isfile(f):
      with open(f, encoding="utf-8") as fh:
        entries = [l.strip() for l in fh if l.strip()]
    present = set(entries) | set(newly_fetched)
    keep = []
    for b in sorted(present):
      parents = self._commit_parents(top, b)
      if parents is None:
        keep.append(b)
        continue
      if any(not self._object_present(top, p) for p in parents):
        keep.append(b)
      else:
        pout(f"checkout: shallow boundary {b} no longer needed "
             f"(parents present)")
    if keep != entries:
      tmp = f + ".tmp"
      with open(tmp, "w", encoding="utf-8") as fh:
        for b in keep:
          fh.write(b + "\n")
      os.replace(tmp, f)
    pout(f"checkout: shallow boundary now {keep}")

  def build_virtual(self, top, src, tree):
    """Deterministic virtual commit: same author/committer/message as src,
    trimmed tree. Parents: copied from src. If a parent is missing locally,
    fetch just that commit from the server (deepen by one layer, updating
    .git/shallow) so git log can walk the real history; only if the fetch
    fails does the view commit fall back to a root."""
    raw = self.git_bin(["git", "cat-file", "commit", src], cwd=top)
    idx = raw.find(b"\n\n")
    if idx < 0:
      raise Exception(f"malformed commit {src}")
    parents = []
    author = None
    committer = None
    enc = None
    for h in raw[:idx].split(b"\n"):
      if h.startswith(b"parent "):
        parents.append(h[7:].decode())
      elif h.startswith(b"author "):
        author = h[7:]
      elif h.startswith(b"committer "):
        committer = h[10:]
      elif h.startswith(b"encoding "):
        enc = h[9:].decode("ascii", "replace")
    msg = raw[idx + 2:]
    if enc and enc.lower() not in ("utf-8", "utf8"):
      msg = msg.decode(enc, errors="replace").encode("utf-8")
    if parents:
      missing = [pr for pr in parents if not self._object_present(top, pr)]
      if missing:
        try:
          self.deepen_commits(top, missing)
        except Exception as e:
          pout(f"checkout: could not deepen history ({e}); "
               f"view commit becomes a root")
          parents = []
        else:
          still = [pr for pr in parents if not self._object_present(top, pr)]
          if still:
            pout(f"checkout: parents still missing {still}; "
                 f"view commit becomes a root")
            parents = []
    out = b"tree " + tree.encode() + b"\n"
    for pr in parents:
      out += b"parent " + pr.encode() + b"\n"
    out += b"author " + author + b"\n"
    out += b"committer " + committer + b"\n"
    out += b"\n" + msg
    return self.git_bin(["git", "hash-object", "-t", "commit", "-w", "--stdin"],
                        cwd=top, data=out).decode().strip()

  def _net_for_origin(self, top):
    """blackgit.Net endpoint for the origin remote; defaults to 127.0.0.1:1666."""
    import re as _re
    try:
      url = self.git_output(["git", "remote", "get-url", "origin"],
                            cwd=top).strip()
    except Exception:
      url = ""
    import blackgit as _blackgit
    host = None
    port = None
    m = _re.match(r"blackw::([^:/]+):(\d+)", url)
    if m:
      host, port = m.group(1), int(m.group(2))
    else:
      m = _re.match(r"https?://([^/]+)/", url)
      if m:
        hp = m.group(1)
        if "@" in hp:
          hp = hp.rsplit("@", 1)[1]
        if ":" in hp:
          h, p = hp.rsplit(":", 1)
          if p.isdigit():
            host, port = h, int(p)
    if host is None:
      return _blackgit.Net()
    return _blackgit.Net(host, port)

  def ensure_blobs(self, top, shas):
    """Make sure every blob sha exists locally. cat-file -e first (in a
    promisor repo git lazy-fetches on its own); the rest are fetched over the
    blackgit wire protocol (index-pack --promisor) as a fallback."""
    missing = []
    for s in dict.fromkeys(shas):
      p = subprocess.run(["git", "-C", top, "cat-file", "-e", s],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
      if p.returncode != 0:
        missing.append(s)
    if not missing:
      return
    pout(f"checkout: fetching {len(missing)} missing blob(s) from server")
    import blackgit as _blackgit
    net = self._net_for_origin(top)
    try:
      pack = net.call("fetch", "\n".join(missing).encode("utf-8"))
    except Exception as e:
      raise Exception(f"checkout: cannot fetch blobs from server: {e}")
    finally:
      try:
        net.close()
      except Exception:
        pass
    if pack:
      p = subprocess.Popen(["git", "-C", top, "index-pack", "--stdin", "--promisor"],
                           stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE)
      _, err = p.communicate(pack)
      if p.returncode != 0:
        raise Exception(f"checkout: server returned no usable blob pack: "
                        f"{err.decode(errors='replace').strip()}")
    still = [s for s in missing
             if subprocess.run(["git", "-C", top, "cat-file", "-e", s],
                               stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL).returncode != 0]
    if still:
      raise Exception(f"checkout: still missing {len(still)} blob(s) after "
                      f"fetch: {still[:5]}")

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

if __name__ == '__main__':
  blackw = BlackGitCli()
  blackw.run(sys.argv)
