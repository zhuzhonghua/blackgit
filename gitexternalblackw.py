import os
import subprocess

def pout(line):
  sys.stdout.write(line)
  if not line.endswith('\n'):
    sys.stdout.write('\n')
  sys.stdout.flush()

def perr(line):
  sys.stderr.write(line)
  if not line.endswith('\n'):
    sys.stderr.write('\n')
  sys.stderr.flush()

class ExternalBlackW:
  def __init__(self):
    pass

  def run(self, argv):
    pout("external blackw run")
    if argv[1] == "init":
      self.do_init(argv)
    else:
      raise Exception(f"unsupport operation {argv[1]}")

  def do_init(self, argv):
    if len(argv) != 3:
      raise Exception("wrong args")

    path = argv[2]
    if os.path.exists(path):
      raise Exception(f"{path} exists")
    os.mkdir(path)
    self.run_cmd(['git', 'init', '.'], cwd=path)
    self.run_cmd(["git", "config", "core.repositoryformatversion", "1"], cwd=path)
    self.run_cmd(['git', 'config', 'core.fsmonitor', 'true'], cwd=path)
    self.run_cmd(['git', 'config', 'core.multipackindex', 'true'], cwd=path)
    self.run_cmd(['git', 'config', 'core.commitgraph', 'true'], cwd=path)
    self.run_cmd(['git', 'config', 'core.sparsecheckout', 'true'], cwd=path)
    self.run_cmd(["git", "config", "extensions.partialclone", "origin"], cwd=path)
    self.run_cmd(["git", "config", "protocol.blackw.allow", "always"], cwd=path)
    self.run_cmd(['git', 'config', 'fetch.writecommitgraph', 'true'], cwd=path)
    self.run_cmd(['git', 'remote', 'add', 'origin', f"blackw::{path}"], cwd=path)
    self.run_cmd(['git', 'config', 'remote.origin.promisor', 'true'], cwd=path)
    self.run_cmd(['git', 'config', 'remote.origin.partialclonefilter', 'combine:blob:none+tree:0'], cwd=path)
    #self.run_cmd(['git', 'config', 'remote.origin.url', f"blackw::{path}"], cwd=path)
    #self.run_cmd(['git', 'remote', 'set-url', 'origin', f"blackw::{path}"], cwd=path)
    self.run_cmd(['git', 'sparse-checkout', 'init', '--cone'], cwd=path)
    self.run_cmd(['git', 'commit-graph', 'write', '--reachable', '--changed-paths'], cwd=path)

  def run_cmd(self, cmd, *arg, **args):
    pout(f"{cmd}")
    with subprocess.Popen(cmd,
                          stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE,
                          stdin=subprocess.PIPE,
                          text=True,
                          *arg, **args) as p:
      out, err = p.communicate()
      if out:
        pout(out)
      if err:
        perr(err)
      if p.returncode != 0:
        raise Exception(err)
