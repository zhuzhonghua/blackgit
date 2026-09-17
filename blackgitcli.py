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

class BlackGitCli:
  def __init__(self):
    pass

  def run(self, argv):
    pout(f"blackw run {argv}")
    if argv[1] == "clone":
      CloneCommand(self).run(argv)
    else:
      raise Exception(f"unsupport operation {argv[1]}")

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
