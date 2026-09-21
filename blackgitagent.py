#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
blackgitagent.py - natural-language assistant for blackgit (OpenAI-compatible).

Entry: `git black` with no arguments enters this REPL (the git-black wrapper
dispatches). The agent talks to an OpenAI-compatible chat/completions endpoint
with tool calling, and drives blackgitcli (ls/branch/clone/follow/update/lock/
locks) plus stock git, a cd tool, and safe python utility operations.

Distribution: pip package; `openai` is a hard dependency (see pyproject.toml
dependencies and requirements.txt), so no import-time fallback is needed.

- Config: `blackagent.*` in repo-local git config when inside a git repo,
  falling back to ~/.blackgit/config.json. First run prompts for baseurl /
  api key / model. ~/.blackgit/config.json may also carry defaults
  (default_clone_url, default_dir) so the agent can clone a known repo
  without the user typing the address every time.
- History: every message is archived to ~/.blackgit/blackagent-history.sqlite3,
  whether the agent runs inside a git repo or not; in-memory history keeps
  only the latest 100 messages and a fresh session starts after each restart.
- Feedback: a dynamic spinner runs on stderr while waiting for the server,
  with a hard timeout (default 120s, tune via `git config blackagent.timeout`).
- Safety: the git tool may run any git command, but destructive ones
  (delete / merge / rebase / force push / reset / clean / ...) ask the user
  for confirmation first. The python tool only exposes a safe whitelist of
  file / path operations - no arbitrary code execution.
"""

import getpass
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import threading
import time

import openai
from openai import OpenAI

MAX_HISTORY = 100          # max messages kept in memory per session
MAX_TOOL_ROUNDS = 8        # max consecutive tool rounds per user turn
DEFAULT_MODEL = "gpt-4o-mini"
DEFAULT_TIMEOUT = 120      # seconds per request
TOOL_TIMEOUT = 300         # seconds per tool command (clone may be slow)
TOOL_OUTPUT_LIMIT = 8000   # tool output truncation length

CFG_PREFIX = "blackagent."
DB_FILENAME = "blackagent-history.sqlite3"
BLACKDIR = os.path.expanduser("~/.blackgit")
CONFIG_FILE = os.path.join(BLACKDIR, "config.json")
CLI_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "blackgitcli.py")

# Model-facing instructions stay Chinese (first target users are Chinese).
SYSTEM_PROMPT = """你是 blackgit 仓库操作助手。blackgit 是一个面向 monorepo 的稀疏/部分克隆 git 客户端，命令形式是 `git black <子命令>`。用户用自然语言描述想做的仓库操作，你通过调用工具完成，再简明汇报。

你可以用的工具：
- blackgit_ls / blackgit_branch / blackgit_clone / blackgit_follow / blackgit_update / blackgit_lock / blackgit_locks：blackgit 特有命令（稀疏视图、受关注文件集、克隆、更新、锁定）。
- git：所有标准 git 操作（status/log/diff/add/commit/push/fetch/stash/...）。blackgitcli 未覆盖的命令都会透传成 git，所以拿不准用哪个工具时就用 git。注意：删除、合并、强制推送等危险操作会先向用户确认，被拒绝时不要重复执行，如实汇报。
- cd：切换工具执行的工作目录（例如克隆后进入新仓库再操作）。
- python：安全的文件/路径工具（读文件、列目录、拼接路径等），不能执行任意代码。

规则：
1. 先调用工具查证，再下结论；工具输出是唯一事实来源，不要编造文件、分支、提交或输出。
2. 工具返回 exit 非 0 时，向用户说明错误并给出可行的下一步，不要谎称成功。
3. 会改变仓库状态的操作（commit、push、merge、reset、checkout、follow 增删、lock 等）先一句话说明意图再执行；只读查询可直接执行。
4. 用简洁的中文回答；命令、路径、输出用代码块。
5. 不确定当前分支/状态时，先跑 git status / git branch 再回答。
6. 不在仓库内时，blackgit clone 是主要入口；克隆后可先用 cd 进入新仓库。"""


# ---------------------------------------------------------------------------
# git / config / repo helpers
# ---------------------------------------------------------------------------

def _run(argv, cwd=None):
  try:
    return subprocess.run(argv, capture_output=True, text=True, cwd=cwd)
  except Exception:
    return None


def git_dir(cwd=None):
  p = _run(["git", "rev-parse", "--absolute-git-dir"], cwd=cwd)
  if p is None or p.returncode != 0:
    return None
  return p.stdout.strip()


def _read_json(path):
  try:
    with open(path, "r", encoding="utf-8") as f:
      return json.load(f)
  except Exception:
    return None


def _write_json(path, obj):
  try:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
      json.dump(obj, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
    return True
  except Exception:
    return False


def config_get(key, cwd=None):
  """Repo-local git config first, then ~/.blackgit/config.json."""
  gd = git_dir(cwd)
  if gd:
    p = _run(["git", "config", "--local", "--get", CFG_PREFIX + key])
    if p is not None and p.returncode == 0 and p.stdout.strip():
      return p.stdout.strip()
  cfg = _read_json(CONFIG_FILE)
  if isinstance(cfg, dict) and cfg.get(key) is not None:
    return str(cfg[key])
  return None


def config_set(key, value, cwd=None):
  """Store in repo-local git config when in a repo, else ~/.blackgit/config.json."""
  gd = git_dir(cwd)
  if gd:
    p = _run(["git", "config", "--local", CFG_PREFIX + key, value])
    return p is not None and p.returncode == 0
  cfg = _read_json(CONFIG_FILE) or {}
  cfg[key] = value
  return _write_json(CONFIG_FILE, cfg)


def repo_info(cwd=None):
  info = {}
  probes = (
    ("top", ["git", "rev-parse", "--show-toplevel"]),
    ("branch", ["git", "symbolic-ref", "--short", "-q", "HEAD"]),
    ("origin", ["git", "remote", "get-url", "origin"]),
  )
  for key, argv in probes:
    p = _run(argv, cwd=cwd)
    info[key] = p.stdout.strip() if p and p.returncode == 0 and p.stdout.strip() else None
  return info


# ---------------------------------------------------------------------------
# History archive (sqlite, under ~/.blackgit; works with or without a repo)
# ---------------------------------------------------------------------------

class HistoryStore:
  """Archives every message; sessions are keyed by start time, and each
  restart begins with an empty in-memory history."""

  def __init__(self, base_dir):
    os.makedirs(base_dir, exist_ok=True)
    self.path = os.path.join(base_dir, DB_FILENAME)
    self.session = time.strftime("%Y%m%d-%H%M%S")
    self._conn = sqlite3.connect(self.path)
    self._conn.execute(
      "CREATE TABLE IF NOT EXISTS messages("
      " id INTEGER PRIMARY KEY AUTOINCREMENT,"
      " session TEXT NOT NULL,"
      " role TEXT NOT NULL,"
      " content TEXT,"
      " created_at TEXT NOT NULL)")
    self._conn.commit()

  def append(self, role, content):
    self._conn.execute(
      "INSERT INTO messages(session, role, content, created_at) VALUES(?,?,?,?)",
      (self.session, role, content, time.strftime("%Y-%m-%d %H:%M:%S")))
    self._conn.commit()

  def close(self):
    self._conn.close()


# ---------------------------------------------------------------------------
# LLM client (openai SDK, OpenAI-compatible endpoints)
# ---------------------------------------------------------------------------

class LLM:
  def __init__(self, baseurl, api_key, model, timeout):
    base = baseurl.rstrip("/")
    if base.endswith("/chat/completions"):
      base = base[: -len("/chat/completions")]   # the SDK appends this path
    self.baseurl = base
    self.model = model
    # max_retries=0: interactive tool needs honest timeout semantics (the SDK
    # would otherwise retry 3x and turn a 120s timeout into ~5 minutes).
    self.client = OpenAI(base_url=base, api_key=api_key,
                         timeout=timeout, max_retries=0)

  def chat(self, messages, tools):
    """Return a unified dict: {role, content[, tool_calls]}. tools=None omits it."""
    kw = {"model": self.model, "messages": messages}
    if tools:
      kw["tools"] = tools
    resp = self.client.chat.completions.create(**kw)
    m = resp.choices[0].message
    out = {"role": "assistant", "content": m.content}
    if m.tool_calls:
      out["tool_calls"] = [
        {"id": tc.id, "type": "function",
         "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
        for tc in m.tool_calls]
    return out


# ---------------------------------------------------------------------------
# Waiting animation (dynamic spinner on stderr; keeps stdout clean)
# ---------------------------------------------------------------------------

class Spinner:
  def __init__(self, label):
    self.label = label
    self._stop = threading.Event()
    self._t = threading.Thread(target=self._spin, daemon=True)

  def _spin(self):
    frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    i = 0
    t0 = time.time()
    while not self._stop.wait(0.1):
      el = int(time.time() - t0)
      sys.stderr.write("\r\x1b[K%s %s %ds" % (self.label, frames[i % len(frames)], el))
      sys.stderr.flush()
      i += 1

  def start(self):
    self._t.start()

  def stop(self):
    self._stop.set()
    self._t.join(timeout=0.5)
    sys.stderr.write("\r\x1b[K")
    sys.stderr.flush()


def api_call(llm, messages, tools, label):
  """Single request entry with spinner + timeout; returns (message_dict, None)
  or (None, error_message)."""
  sp = Spinner(label)
  sp.start()
  try:
    return llm.chat(messages, tools), None
  except openai.APITimeoutError as e:
    return None, "[超时] %s（可调大：git config blackagent.timeout 300）" % e
  except openai.APIConnectionError as e:
    return None, "[网络/连接失败] %s" % e
  except openai.AuthenticationError as e:
    return None, "[鉴权失败] %s（检查 api key 是否有效）" % e
  except openai.RateLimitError as e:
    return None, "[限流/额度不足] %s" % e
  except openai.BadRequestError as e:
    return None, "[请求被拒] %s（检查 baseurl/model 是否匹配）" % e
  except openai.APIError as e:
    return None, "[接口错误] %s" % e
  except Exception as e:
    return None, "[错误] %s" % e
  finally:
    sp.stop()


# ---------------------------------------------------------------------------
# Tool definitions (OpenAI function calling schema)
# ---------------------------------------------------------------------------

TOOLS = [
  {"type": "function", "function": {
    "name": "blackgit_ls",
    "description": "git black ls：列出仓库树（HEAD 或指定 ref/路径）下的一级目录项，只会显示有权限的文件。参数为空则列 HEAD 根目录。",
    "parameters": {"type": "object", "properties": {
      "ref_or_path": {"type": "string", "description": "可选：ref、路径或 branch:path，例如 src/java 或 HEAD:src"}},
      "required": []}}},
  {"type": "function", "function": {
    "name": "blackgit_branch",
    "description": "git black branch：列出本地分支、远程分支及当前分支。无参数。",
    "parameters": {"type": "object", "properties": {}}}},
  {"type": "function", "function": {
    "name": "blackgit_clone",
    "description": "git black clone <url> [<dir>]：对 monorepo 做稀疏/部分克隆（仅支持 http(s)://xxx/xxx.git 形式的 URL）。未给 url 时可用默认克隆地址。",
    "parameters": {"type": "object", "properties": {
      "url": {"type": "string", "description": "形如 https://host/repo.git 的克隆地址（留空则用默认地址）"},
      "dest": {"type": "string", "description": "可选目标目录，默认取仓库名"}},
      "required": []}}},
  {"type": "function", "function": {
    "name": "blackgit_follow",
    "description": "git black follow：把文件/目录加入或移出『受关注集』（worktree 稀疏视图，只物化受关注文件）。action=list 时列出当前受关注文件；action=delete 会先经用户确认。",
    "parameters": {"type": "object", "properties": {
      "action": {"type": "string", "enum": ["add", "delete", "list"],
                 "description": "add=关注，delete=取消关注（对应 -d），list=列出（对应 -l）"},
      "paths": {"type": "array", "items": {"type": "string"},
                "description": "仓库内文件/目录路径（action=list 时忽略）"},
      "recursive": {"type": "boolean", "description": "目录是否递归（对应 -r），默认 false"}},
      "required": ["action"]}}},
  {"type": "function", "function": {
    "name": "blackgit_update",
    "description": "git black update：把当前分支快进到 origin 最新（只拉缺失的 commit，blob 按需获取）。会移动分支引用，执行前会向用户确认。无参数。",
    "parameters": {"type": "object", "properties": {}}}},
  {"type": "function", "function": {
    "name": "blackgit_lock",
    "description": "git black lock <path>：锁定/解锁某个文件（unlock=true 时对应 lock -d）。",
    "parameters": {"type": "object", "properties": {
      "path": {"type": "string", "description": "要锁定/解锁的仓库内文件路径"},
      "unlock": {"type": "boolean", "description": "true=解锁，默认 false=锁定"}},
      "required": ["path"]}}},
  {"type": "function", "function": {
    "name": "blackgit_locks",
    "description": "git black locks：列出当前被锁定的文件。无参数。",
    "parameters": {"type": "object", "properties": {}}}},
  {"type": "function", "function": {
    "name": "git",
    "description": "执行任意标准 git 命令。blackgitcli 未覆盖的命令都会透传成 git，所以除 blackgit 专用命令外的操作（status/log/diff/add/commit/push/branch -a/show 等）都用本工具。删除、合并、rebase、强制推送、reset、clean 等危险命令会先向用户确认。",
    "parameters": {"type": "object", "properties": {
      "args": {"type": "string", "description": "完整 git 子命令及参数，例如 status 或 log --oneline -5"}},
      "required": ["args"]}}},
  {"type": "function", "function": {
    "name": "cd",
    "description": "切换后续工具执行的工作目录（例如克隆完成后进入新仓库目录）。切换后会返回新目录及其 git 状态。",
    "parameters": {"type": "object", "properties": {
      "dir": {"type": "string", "description": "目标目录，可为绝对路径或相对当前目录的路径；留空则回到主目录"}},
      "required": ["dir"]}}},
  {"type": "function", "function": {
    "name": "python",
    "description": "执行预定义的安全 Python 文件/路径工具（不允许任意代码执行）：read_file 读文本、write_file 写文本、list_dir 列目录、abs_path 绝对路径、join_path 拼接路径、exists 判断存在、current_dir 当前目录。",
    "parameters": {"type": "object", "properties": {
      "op": {"type": "string",
             "enum": ["read_file", "write_file", "list_dir", "abs_path", "join_path", "exists", "current_dir"],
             "description": "要执行的安全操作"},
      "path": {"type": "string", "description": "路径参数"},
      "path2": {"type": "string", "description": "join_path 的第二个路径"},
      "content": {"type": "string", "description": "write_file 的写入内容"},
      "rel": {"type": "boolean", "description": "相对路径是否基于当前工作目录解析，默认 true"}},
      "required": ["op"]}}},
]


def _exec(argv, display, cwd=None):
  try:
    p = subprocess.run(argv, capture_output=True, text=True,
                       timeout=TOOL_TIMEOUT, cwd=cwd)
  except subprocess.TimeoutExpired:
    return "$ %s\n(命令超时（%ss）被终止)" % (display, TOOL_TIMEOUT)
  except Exception as e:
    return "$ %s\n(执行失败: %s)" % (display, e)
  out = p.stdout or ""
  if p.stderr:
    out += ("\n" if out else "") + p.stderr
  lines = out.splitlines()
  if lines and lines[0].startswith("blackw run "):   # strip blackgitcli debug line
    lines = lines[1:]
  out = "\n".join(lines).strip()
  if len(out) > TOOL_OUTPUT_LIMIT:
    out = out[:TOOL_OUTPUT_LIMIT] + "\n...(输出过长，已截断)"
  head = "$ " + display
  if p.returncode != 0:
    head += "  [exit %d]" % p.returncode
  return (head + "\n" + out).strip() if out else head + "  (无输出)"


# ---------------------------------------------------------------------------
# Agent: history trimming + tool loop
# ---------------------------------------------------------------------------

class BlackGitAgent:
  def __init__(self, llm, store, cwd=None, defaults=None):
    self.llm = llm
    self.store = store
    self.cwd = os.path.realpath(cwd or os.getcwd())
    self.defaults = defaults or {}
    self.history = []

  # -- system prompt -------------------------------------------------------

  def _system_prompt(self):
    info = repo_info(self.cwd)
    lines = [SYSTEM_PROMPT, "", "当前工作目录: %s" % self.cwd]
    if info.get("top"):
      lines += ["- top-level: %s" % info["top"],
                "- 当前分支: %s" % (info.get("branch") or "(无/detached)"),
                "- origin: %s" % (info.get("origin") or "无")]
    else:
      lines.append("（当前不在 git 仓库内；blackgit clone 可在此目录创建新仓库）")
    if self.defaults.get("default_clone_url"):
      lines.append("- 默认克隆地址（~/.blackgit 配置，用户未指定 url 时使用）: %s"
                   % self.defaults["default_clone_url"])
    if self.defaults.get("default_dir"):
      lines.append("- 默认目录（~/.blackgit 配置）: %s" % self.defaults["default_dir"])
    return "\n".join(lines)

  # -- history -------------------------------------------------------------

  def _add(self, msg):
    self.history.append(msg)
    self.store.append(msg.get("role"), json.dumps(msg, ensure_ascii=False))
    self._trim()

  def _trim(self):
    """Keep at most MAX_HISTORY messages; never split an assistant(tool_calls)
    message from its following tool results, or the next request would carry a
    dangling tool call."""
    while len(self.history) > MAX_HISTORY:
      m = self.history[0]
      if m.get("role") == "assistant" and m.get("tool_calls"):
        j = 1
        while j < len(self.history) and self.history[j].get("role") == "tool":
          j += 1
        del self.history[:j]
      else:
        del self.history[:1]

  # -- conversation turn ---------------------------------------------------

  def turn(self, text):
    self._add({"role": "user", "content": text})
    for rnd in range(1, MAX_TOOL_ROUNDS + 1):
      msg, err = api_call(
        self.llm, [{"role": "system", "content": self._system_prompt()}] + self.history,
        TOOLS, "模型思考中")
      if err:
        print(err)
        return
      if msg.get("tool_calls"):
        self._add(msg)
        for tc in msg["tool_calls"]:
          fn = tc.get("function", {})
          name = fn.get("name", "")
          try:
            args = json.loads(fn.get("arguments") or "{}")
            if not isinstance(args, dict):
              args = {}
          except Exception:
            args = {}
          result = self.execute_tool(name, args)
          print(result)   # REPL transparency: show the tool execution first
          self._add({"role": "tool", "tool_call_id": tc.get("id", ""), "content": result})
        continue
      content = msg.get("content") or ""
      self._add({"role": "assistant", "content": content})
      print(content) if content else print("(模型返回了空内容)")
      return
    print("(达到最大工具轮数 %d，请重新描述或检查上一步)" % MAX_TOOL_ROUNDS)

  # -- tool execution ------------------------------------------------------

  def execute_tool(self, name, args):
    if name == "git":
      argv = ["git"] + shlex.split(args.get("args", ""))
      display = "git " + args.get("args", "")
      if self._needs_confirm("git", args.get("args", "")) and not self._confirm(display):
        return "$ %s\n(已取消：用户未确认)" % display
      return _exec(argv, display, self.cwd)
    if name == "cd":
      return self._cd(args)
    if name == "python":
      return self._python_op(args)
    if not name.startswith("blackgit_"):
      return "未知工具: %s" % name
    cmd = name[len("blackgit_"):]
    argv = [sys.executable, CLI_PATH, cmd]
    if cmd == "ls":
      if args.get("ref_or_path"):
        argv.append(args["ref_or_path"])
    elif cmd == "clone":
      argv.append(args.get("url", "") or self.defaults.get("default_clone_url", ""))
      if args.get("dest"):
        argv.append(args["dest"])
    elif cmd == "follow":
      action = args.get("action", "add")
      if action == "list":
        argv.append("-l")
      else:
        if action == "delete":
          if not self._confirm("git black follow -d %s" % " ".join(args.get("paths", []))):
            return "$ git black follow -d\n(已取消：用户未确认)"
          argv.append("-d")
        if args.get("recursive"):
          argv.append("-r")
        argv += [p for p in args.get("paths", [])]
    elif cmd == "update":
      if not self._confirm("git black update"):
        return "$ git black update\n(已取消：用户未确认)"
    elif cmd == "lock":
      if args.get("unlock"):
        argv.append("-d")
      argv.append(args.get("path", ""))
    return _exec(argv, "git black " + " ".join(argv[2:]), self.cwd)

  def _cd(self, args):
    target = args.get("dir", "") or "~"
    new = os.path.expanduser(target)
    if not os.path.isabs(new):
      new = os.path.join(self.cwd, new)
    new = os.path.realpath(new)
    if not os.path.isdir(new):
      return "cd: 目录不存在: %s" % args.get("dir", "")
    self.cwd = new
    info = repo_info(new)
    if info.get("top"):
      extra = "（仓库: %s，分支: %s）" % (info["top"], info.get("branch") or "无")
    else:
      extra = "（不在 git 仓库内）"
    return "已切换到: %s %s" % (new, extra)

  def _python_op(self, args):
    """Safe whitelist of file/path utilities - no arbitrary code execution."""
    op = args.get("op", "")

    def res(p, rel=True):
      p = os.path.expanduser(str(p or ""))
      if not os.path.isabs(p) and rel:
        p = os.path.join(self.cwd, p)
      return os.path.realpath(p)

    try:
      if op == "current_dir":
        return self.cwd
      if op == "abs_path":
        return res(args.get("path", ""))
      if op == "join_path":
        return res(os.path.join(str(args.get("path", "")), str(args.get("path2", ""))))
      if op == "exists":
        return "%s" % os.path.exists(res(args.get("path", "")))
      if op == "list_dir":
        p = res(args.get("path", "."))
        if not os.path.isdir(p):
          return "不是目录: %s" % args.get("path", "")
        entries = sorted(os.listdir(p))
        lines = []
        for e in entries[:200]:
          full = os.path.join(p, e)
          kind = "dir " if os.path.isdir(full) else "file"
          lines.append("%s\t%s" % (kind, e))
        return "\n".join(lines) if lines else "(empty)"
      if op == "read_file":
        p = res(args.get("path", ""))
        if not os.path.isfile(p):
          return "文件不存在: %s" % args.get("path", "")
        with open(p, "r", encoding="utf-8", errors="replace") as f:
          data = f.read(4000)
        return data + ("\n...(已截断)" if len(data) >= 4000 else "")
      if op == "write_file":
        p = res(args.get("path", ""))
        d = os.path.dirname(p)
        if d and not os.path.isdir(d):
          os.makedirs(d, exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
          f.write(args.get("content", ""))
        return "已写入: %s" % p
    except Exception as e:
      return "python %s 失败: %s" % (op, e)
    return "未知 python 操作: %s" % op

  # -- safety: dangerous operations need user confirmation ------------------

  def _needs_confirm(self, name, args):
    if name == "blackgit_follow":
      return args.get("action") == "delete"
    if name == "blackgit_update":
      return True
    if name != "git":
      return False
    try:
      toks = shlex.split(args)
    except Exception:
      toks = args.split()
    if not toks:
      return False
    verb = toks[0]
    if verb in ("reset", "clean", "rm", "merge", "rebase", "revert",
                "cherry-pick", "restore", "filter-branch", "update-ref",
                "gc", "prune"):
      return True
    if verb == "push":
      return any(t in ("-f", "--force", "--delete") or t.startswith("--delete")
                 for t in toks)
    if verb == "branch":
      return "-D" in toks or "--delete" in toks
    if verb == "tag":
      return "-d" in toks or "--delete" in toks
    if verb == "stash":
      return toks[1:2] and toks[1] in ("drop", "clear")
    if verb == "checkout":
      return "-f" in toks or "--" in toks or "." in toks
    if verb == "switch":
      return "-f" in toks
    return False

  def _confirm(self, display):
    try:
      ans = input("危险操作：%s\n确认执行？[y/N] " % display).strip().lower()
    except (EOFError, KeyboardInterrupt):
      return False
    return ans in ("y", "yes")


# ---------------------------------------------------------------------------
# REPL
# ---------------------------------------------------------------------------

def repl(agent):
  print("blackgit agent — 用自然语言操作 blackgit / git 仓库")
  print("工具: blackgit(ls/branch/clone/follow/update/lock/locks) + git + cd + python(安全白名单)")
  print("exit / Ctrl-D 退出；Ctrl-C 中断当前等待；危险 git 操作会先向你确认")
  print("历史: 本会话保留最近 %d 条，重启后从零开始（存档: %s）"
        % (MAX_HISTORY, os.path.join(BLACKDIR, DB_FILENAME)))
  while True:
    try:
      line = input("black> ")
    except EOFError:
      print()
      return 0
    except KeyboardInterrupt:
      print()
      continue
    s = line.strip()
    if not s:
      continue
    if s in ("exit", "quit", "q", "退出"):
      return 0
    agent.turn(s)


# ---------------------------------------------------------------------------
# Config & entry
# ---------------------------------------------------------------------------

def ensure_config(cwd):
  """Prompt for baseurl/api key/model on first run and persist them; reads
  everything (including optional defaults) from repo config or ~/.blackgit."""
  baseurl = config_get("baseurl", cwd)
  apikey = config_get("apikey", cwd)
  if not baseurl or not apikey:
    print("首次运行：需要配置 OpenAI 兼容接口（baseurl / api key）。")
    print("写入位置：当前仓库的 [%s] 段（git config --local），非仓库目录时写入 %s。"
          % (CFG_PREFIX.rstrip("."), CONFIG_FILE))
    if not baseurl:
      v = input("  base url: ").strip()
      if v:
        config_set("baseurl", v, cwd)
        baseurl = v
    if not apikey:
      try:
        v = getpass.getpass("  api key: ").strip()
      except Exception:
        v = input("  api key: ").strip()
      if v:
        config_set("apikey", v, cwd)
        apikey = v
    if not baseurl or not apikey:
      print("配置不完整，无法启动。", file=sys.stderr)
      return None, None, None, None, None
    if not config_get("model", cwd):
      m = input("  model（默认 %s）: " % DEFAULT_MODEL).strip()
      config_set("model", m or DEFAULT_MODEL, cwd)
  model = config_get("model", cwd) or DEFAULT_MODEL
  try:
    timeout = int(config_get("timeout", cwd) or DEFAULT_TIMEOUT)
  except (TypeError, ValueError):
    timeout = DEFAULT_TIMEOUT
  defaults = {
    "default_clone_url": config_get("default_clone_url", cwd),
    "default_dir": config_get("default_dir", cwd),
  }
  return baseurl, apikey, model, timeout, defaults


def main():
  cwd = os.getcwd()
  baseurl, apikey, model, timeout, defaults = ensure_config(cwd)
  if not baseurl or not apikey:
    return 2

  llm = LLM(baseurl, apikey, model, timeout)
  store = HistoryStore(BLACKDIR)
  agent = BlackGitAgent(llm, store, cwd=cwd, defaults=defaults)
  try:
    return repl(agent)
  finally:
    store.close()


if __name__ == "__main__":
  sys.exit(main())
