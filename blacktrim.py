#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
blacktrim.py — client-side subtree trim + virtual commit + rebase update.

Pure local logic: it never talks to the server. Data (the real commit objects)
is fetched beforehand with the standard git command `git fetch origin <sha>`
(the clone configures remote.origin.url). blacktrim only computes the
deterministic virtual commit vc = trim(sha, path) — the exact mirror of the
server's VirtualCommit.java — maintains the local map vc -> (real, path) in
.git/blackw-trim-map.tsv, and rebases the local work onto a rebuilt view when
the real commit moves.

Usage
-----
    python3 blacktrim.py [--top DIR] [--force] <sha> <path>
        vc = trim(<sha>, <path>); record vc -> (<sha>, <path>); pin
        refs/blackw/trim/<sha8>/<path>; move HEAD to vc.  <sha> must exist
        locally (git fetch origin <sha>).  path "." is the identity view
        (vc == <sha>, not recorded).

    python3 blacktrim.py [--top DIR] --rebase <new-real-sha> [path]
        Rebuild the view from a new real commit already fetched locally:
        vc' = trim(<new-real-sha>, <path>); record vc' -> (<new-real-sha>, <path>);
        then rebase the local work from the old base vc onto vc'
        (git rebase --onto vc' vc).  [path] is optional and must match the
        recorded base path.
"""

import argparse
import os
import re
import subprocess
import sys

# Pinned trim cache (HEAD holds the latest trimmed commit).
REF_CACHE_PREFIX = "refs/blackw/trim"
MAP_FILE = "blackw-trim-map.tsv"
ROOT = "."


def perr(line):
  sys.stderr.write(line)
  if not line.endswith("\n"):
    sys.stderr.write("\n")
  sys.stderr.flush()


def run(top, args, data=None):
  return subprocess.run(["git", "-C", top, *args], input=data,
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def git(top, *args):
  p = run(top, args)
  if p.returncode != 0:
    raise RuntimeError(p.stderr.decode(errors="replace").strip()
                       or "git %s failed" % " ".join(args))
  return p.stdout.decode("utf-8", "replace")


def gitq(top, *args):
  p = run(top, args)
  return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr


def gitb(top, *args, data=None):
  p = run(top, args, data)
  if p.returncode != 0:
    raise RuntimeError(p.stderr.decode(errors="replace").strip()
                       or "git %s failed" % " ".join(args))
  return p.stdout


def sanitize_path(path):
  if path in ("", ".", "/"):
    return "root"
  out = []
  for seg in path.strip("/").split("/"):
    s = re.sub(r"[^A-Za-z0-9._-]", "-", seg)
    if not s or s in (".", ".."):
      s = "_"
    out.append(s)
  return "/".join(out)


def cache_ref(sha, path):
  return "%s/%s/%s" % (REF_CACHE_PREFIX, sha[:8], sanitize_path(path))


def is_valid_sha(sha):
  return len(sha) == 40 and all(c in "0123456789abcdefABCDEF" for c in sha)


def pin_head(top, sha):
  git(top, "update-ref", "--no-deref", "HEAD", sha)


def object_exists(top, sha):
  return gitq(top, "cat-file", "-e", sha)[0] == 0


# ---------------------------------------------------------------- local trim

def normalize_path(path):
  """Mirrors VirtualCommit.normalizePath: '.' for empty/root, no ./ or /."""
  if path is None:
    return ROOT
  p = path.strip()
  while p.startswith("./"):
    p = p[2:]
  while p.endswith("/"):
    p = p[:-1]
  if p.startswith("/"):
    p = p[1:]
  return ROOT if p == "" else p


def cat_commit(top, sha):
  raw = gitb(top, "cat-file", "commit", sha)
  idx = raw.find(b"\n\n")
  if idx < 0:
    raise RuntimeError("malformed commit %s" % sha)
  c = {"tree": None, "parents": [], "author": None,
       "committer": None, "encoding": None}
  for h in raw[:idx].split(b"\n"):
    if h.startswith(b"tree "):
      c["tree"] = h[5:].decode()
    elif h.startswith(b"parent "):
      c["parents"].append(h[7:].decode())
    elif h.startswith(b"author "):
      c["author"] = h[7:]
    elif h.startswith(b"committer "):
      c["committer"] = h[10:]
    elif h.startswith(b"encoding "):
      c["encoding"] = h[9:].decode("ascii", "replace")
  msg = raw[idx + 2:]
  enc = c["encoding"]
  if enc and enc.lower() not in ("utf-8", "utf8"):
    msg = msg.decode(enc, errors="replace").encode("utf-8")
  c["message"] = msg
  return c


def ls_tree(top, tree):
  out = gitb(top, "ls-tree", "-z", tree)
  entries = []
  for raw in out.split(b"\0"):
    if not raw:
      continue
    meta, _, name = raw.partition(b"\t")
    mode, typ, sha = meta.split(b" ", 2)
    entries.append((mode.decode(), typ.decode(), sha.decode(),
                    name.decode("utf-8", "replace")))
  return entries


def mktree(top, entries):
  payload = b"".join(("%s %s %s\t%s" % (m, t, s, n)).encode("utf-8") + b"\0"
                     for (m, t, s, n) in entries)
  p = subprocess.run(["git", "-C", top, "mktree", "-z"], input=payload,
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
  if p.returncode != 0:
    raise RuntimeError(p.stderr.decode(errors="replace").strip() or "mktree failed")
  return p.stdout.decode().strip()


def resolve_tree(top, sha, path):
  rc, out, _ = gitq(top, "rev-parse", "%s:%s" % (sha, path))
  if rc != 0:
    raise RuntimeError("no path '%s' in commit %s" % (path, sha))
  return out.strip()


def trim_view(top, tree, segs, leaf):
  """Mirrors VirtualCommit.trimView: single-entry chain to the trimmed subtree."""
  first = segs[0]
  entry = None
  for e in ls_tree(top, tree):
    if e[3] == first:
      entry = e
      break
  if entry is None:
    raise RuntimeError("no path segment '%s' in tree %s" % (first, tree))
  if len(segs) == 1:
    child = leaf
  else:
    if entry[1] != "tree":
      raise RuntimeError("path segment '%s' in the trimmed tree is not a directory" % first)
    child = trim_view(top, entry[2], segs[1:], leaf)
  return mktree(top, [("040000", "tree", child, first)])


def build_commit(top, src, view):
  """Mirrors VirtualCommit.buildVirtual: verbatim parents/author/committer/message."""
  c = cat_commit(top, src)
  out = b"tree " + view.encode() + b"\n"
  for pr in c["parents"]:
    out += b"parent " + pr.encode() + b"\n"
  out += b"author " + c["author"] + b"\n"
  out += b"committer " + c["committer"] + b"\n"
  out += b"\n" + c["message"]
  return gitb(top, "hash-object", "-t", "commit", "-w", "--stdin", data=out).decode().strip()


def trim_local(top, sha, path):
  """Deterministic vc = trim(<sha>, <path>); identity for path '.'."""
  p = normalize_path(path)
  if p == ROOT:
    return sha
  leaf = resolve_tree(top, sha, p)
  rc, typ, _ = gitq(top, "cat-file", "-t", leaf)
  if rc != 0 or typ.strip() != "tree":
    raise RuntimeError("path '%s' in commit %s is not a tree" % (p, sha))
  segs = [s for s in p.split("/") if s]
  root = git(top, "rev-parse", sha + "^{tree}").strip()
  view = trim_view(top, root, segs, leaf)
  return build_commit(top, sha, view)


# ---------------------------------------------------------------- local map

def map_file(top):
  return os.path.join(git(top, "rev-parse", "--absolute-git-dir").strip(), MAP_FILE)


def read_map(top):
  mp = {}
  f = map_file(top)
  if os.path.isfile(f):
    with open(f, encoding="utf-8") as fh:
      for line in fh:
        line = line.rstrip("\n")
        if not line:
          continue
        parts = line.split("\t")
        if len(parts) >= 3:
          mp[parts[0]] = {"real": parts[1], "path": parts[2]}
  return mp


def write_map(top, mp):
  f = map_file(top)
  tmp = f + ".tmp"
  with open(tmp, "w", encoding="utf-8") as fh:
    for vc in sorted(mp):
      e = mp[vc]
      fh.write("%s\t%s\t%s\n" % (vc, e["real"], e["path"]))
  os.replace(tmp, f)


def record_map(top, vc, real, path):
  mp = read_map(top)
  mp[vc] = {"real": real, "path": path}
  write_map(top, mp)


def find_base(top):
  """Nearest known trim base on the current branch's first-parent chain."""
  mp = read_map(top)
  chain = git(top, "rev-list", "--first-parent", "HEAD").split()
  for sha in chain:
    if sha in mp:
      return sha, mp[sha]
  return None


# ---------------------------------------------------------------- rebase

def rebase_view(top, new_real, want_path=None):
  base = find_base(top)
  if base is None:
    raise RuntimeError("no known trim base in the current branch history; run trim first")
  vc, e = base
  path = e["path"]
  if want_path and path != want_path:
    raise RuntimeError("base %s is for path %s, not %s" % (vc, path, want_path))
  if not object_exists(top, new_real):
    raise RuntimeError("real commit %s not present locally; run: git fetch origin %s"
                       % (new_real, new_real))
  if new_real == e["real"]:
    print("up to date: real still %s" % new_real)
    return 0

  vc2 = trim_local(top, new_real, path)
  record_map(top, vc2, new_real, path)
  git(top, "update-ref", cache_ref(new_real, path), vc2)
  print("new view %s (real %s, path %s)" % (vc2, new_real, path))

  head = git(top, "rev-parse", "HEAD").strip()
  if head == vc:
    print("no local commits on top; moving HEAD %s -> %s" % (vc, vc2))
    pin_head(top, vc2)
  else:
    print("rebasing local work onto %s" % vc2)
    p = subprocess.run(["git", "-C", top, "rebase", "--onto", vc2, vc],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
      sys.stderr.write(p.stdout.decode(errors="replace"))
      sys.stderr.write(p.stderr.decode(errors="replace"))
      raise RuntimeError("rebase stopped with conflicts; resolve and run git rebase --continue")
  print("base now %s" % vc2)
  return 0


# ---------------------------------------------------------------- cli

def main(argv):
  ap = argparse.ArgumentParser(prog="blacktrim.py",
                               description="client-side subtree trim + virtual commit + rebase")
  ap.add_argument("--top", default=None, help="repo top-level (default: cwd)")
  ap.add_argument("--force", action="store_true",
                  help="ignore the local (sha, path) cache and recompute")
  ap.add_argument("--rebase", metavar="NEW_REAL",
                  help="rebuild the view from NEW_REAL (fetched locally) and rebase onto it")
  ap.add_argument("sha", nargs="?")
  ap.add_argument("path", nargs="?")
  a = ap.parse_args(argv)

  try:
    top = os.path.realpath(a.top) if a.top else os.getcwd()
    top = os.path.realpath(git(top, "rev-parse", "--show-toplevel").strip())
  except RuntimeError as e:
    perr(str(e))
    return 2

  try:
    if a.rebase:
      if a.sha:
        perr("--rebase takes no <sha>; use [path] to constrain the base path")
        return 2
      return rebase_view(top, a.rebase.strip(), want_path=a.path)

    if not a.sha or a.path is None:
      ap.print_usage()
      return 2
    sha = a.sha.strip()
    if not is_valid_sha(sha):
      perr("bad sha: %r (want 40 hex chars)" % a.sha)
      return 2

    p = normalize_path(a.path)
    cref = cache_ref(sha, p)
  virtual = None
  if not a.force:
      q = gitq(top, "rev-parse", "--verify", "--quiet", cref)
      if q[0] == 0:
        virtual = q[1].strip()

  if virtual is None:
      if not object_exists(top, sha):
        perr("real commit %s not present locally; run: git fetch origin %s" % (sha, sha))
      return 1
      virtual = trim_local(top, sha, p)
    print("trim %s:/%s -> %s" % (sha, a.path, virtual))
      if p != ROOT:
        record_map(top, virtual, sha, p)
    git(top, "update-ref", cref, virtual)
    print("pinned %s -> %s" % (cref, virtual))
    else:
      print("cached %s = %s" % (cref, virtual))
      if p != ROOT:
        mp = read_map(top)
        if virtual not in mp:
          record_map(top, virtual, sha, p)

  pin_head(top, virtual)
  print("HEAD -> %s" % virtual)
  return 0
  except Exception as e:
    perr(str(e))
    return 1


if __name__ == "__main__":
  sys.exit(main(sys.argv[1:]))
