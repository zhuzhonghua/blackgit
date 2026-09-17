#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
blacktrim.py — fetch a subtree-trimmed *virtual commit* from a BlackGit server.

What it does
------------
Given a commit sha you already have locally and a subtree path, it asks the
server for a josh-style virtual commit: a normal git commit object whose root
tree keeps the requested subtree *in place* — the directory structure up to
<path> is preserved, only the unrelated paths are trimmed away. The author /
committer / parents / message are carried over verbatim (no marker is added).
The server builds it deterministically, so re-requesting the same (commit,
path) always yields the same object id.

HEAD is moved to the resulting virtual commit so you can build new commits on
top of it (git commit / commit-tree apply to the virtual view). The pinned ref

    refs/blackw/trim/<sha8>/<path-sanitized>

keeps the object alive for git gc and makes a repeated call for the same
source commit and path fully offline (no server round-trip).

Usage
-----
    python3 blacktrim.py [--top DIR] [--host H] [--port P] [--force] <sha> <path>

    <sha>    source commit you want trimmed (must exist locally)
    <path>   subtree path; use "." for the whole tree (identity)

Examples
--------
    # virtual commit of docs/ (kept under docs/) from HEAD
    python3 blacktrim.py $(git rev-parse HEAD) docs

    # whole tree (non-recursive), an identity view of the source commit
    python3 blacktrim.py $(git rev-parse HEAD) .

Notes
-----
The interface is provisional (the wire format and ref names are easy to
change). The server side lives in the 'trim' protocol / VirtualCommit.java.
"""

import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import blackgit  # noqa: E402

# Pinned trim cache (HEAD holds the latest trimmed commit).
REF_CACHE_PREFIX = "refs/blackw/trim"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 1666


def perr(line):
  sys.stderr.write(line)
  if not line.endswith("\n"):
    sys.stderr.write("\n")
  sys.stderr.flush()


def gitquiet(top, *args):
  return subprocess.run(["git", "-C", top, *args],
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def git(top, *args):
  p = gitquiet(top, *args)
  if p.returncode != 0:
    raise RuntimeError(p.stderr.strip() or "git %s failed" % " ".join(args))
  return p.stdout


def index_pack(top, pack):
  p = subprocess.run(["git", "-C", top, "index-pack", "--stdin", "--promisor"],
                     input=pack, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
  if p.returncode != 0:
    msg = p.stderr.decode(errors="replace").strip() if p.stderr else ""
    raise RuntimeError(msg or "index-pack failed")


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
  if len(sha) != 40:
    return False
  try:
    int(sha, 16)
  except ValueError:
    return False
  return True


def pin_head(top, sha):
  git(top, "update-ref", "--no-deref", "HEAD", sha)


def main(argv):
  ap = argparse.ArgumentParser(prog="blacktrim.py")
  ap.add_argument("--top", default=None, help="repo top-level (default: cwd)")
  ap.add_argument("--host", default=DEFAULT_HOST)
  ap.add_argument("--port", type=int, default=DEFAULT_PORT)
  ap.add_argument("--force", action="store_true",
                  help="hit the server even if the (sha, path) cache ref exists")
  ap.add_argument("sha")
  ap.add_argument("path")
  a = ap.parse_args(argv)

  sha = a.sha.strip()
  if not is_valid_sha(sha):
    perr("bad sha: %r (want 40 hex chars)" % a.sha)
    return 2

  try:
    top = os.path.realpath(a.top) if a.top else os.getcwd()
    out = git(top, "rev-parse", "--show-toplevel")
    top = os.path.realpath(out.strip())
  except RuntimeError as e:
    perr(str(e))
    return 2

  cref = cache_ref(sha, a.path)
  virtual = None
  if not a.force:
    p = gitquiet(top, "rev-parse", "--verify", "--quiet", cref)
    if p.returncode == 0:
      print("cached %s = %s" % (cref, p.stdout.strip()))
      virtual = p.stdout.strip()

  if virtual is None:
    resp = None
    try:
      net = blackgit.Net(a.host, a.port)
    except Exception as e:
      perr("cannot reach blackgit server %s:%s: %s" % (a.host, a.port, e))
      return 1
    try:
      body = b"sha=" + sha.encode("ascii") + b"\npath=" + a.path.encode("utf-8") + b"\n"
      resp = net.call("trim", body)
    finally:
      try:
        net.close()
      except Exception:
        pass

    head, sep, pack = resp.partition(b"\n")
    line = head.decode("utf-8", errors="replace").strip()
    if line.startswith("ERROR:"):
      perr(line)
      return 1
    if not sep or len(line) != 40:
      perr("bad trim reply: %r" % line)
      return 1
    virtual = line

    if pack:
      index_pack(top, pack)

    print("trim %s:/%s -> %s" % (sha, a.path, virtual))
    git(top, "update-ref", cref, virtual)
    print("pinned %s -> %s" % (cref, virtual))

  pin_head(top, virtual)
  print("HEAD -> %s" % virtual)
  return 0


if __name__ == "__main__":
  e = main(sys.argv[1:])
  sys.exit(e if isinstance(e, int) else int(e or 0))