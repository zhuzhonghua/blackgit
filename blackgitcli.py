import subprocess
import sys
import os

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

  def fetch(self, dest):
    self.blackw.run_cmd(['git', 'fetch', '--depth=1', 'origin'], cwd=dest)

  def sparsify(self, dest):
    self.blackw.run_cmd(['git', 'sparse-checkout', 'set', '--no-cone',
                         '!/*', '!/*/*'], cwd=dest)
    pout("clone: sparse checkout on (empty worktree)")

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
    run(['git', 'checkout', '-b', branch, f'origin/{branch}'], cwd=dest)

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
    else:
      raise Exception(f"unsupport operation {argv[1]}")

  def _top(self):
    if self.toplevel is None:
      self.toplevel = self.git_output(["git", "rev-parse", "--show-toplevel"]).strip()
    return self.toplevel

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
