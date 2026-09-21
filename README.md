## BlackGit

BlackGit is composed by client and server sides(the client and server are decoupled),
two pieces make it useful for large, permission-sensitive repos:


- **Cli** **Partial clone + sparse, per-user views on the client** (`git black`, Python) — wraps stock git:
Initially clone with `blob:none`, sparse-checkout set !/* !/*/* checkout nothing
then materialize only the files the user explicitly `follow`s.
No LFS, no full history of a multi-GB repo.
- **Cli Agent**
A mini agent to use natural languages to control blackgit and git
- **Server** path-level blob authorization
BlackGit Server sits in front of a standard Git server (GitLab/GitHub) as a smart-HTTP cache and access-control layer,
refuses to hand out blobs that fall outside the caller's authorized paths.

---

### How it works

BlackGit Cli (Python) could be used spearately to handle Github/GitLab repositories.

BlackGit Server is a standard **git smart-HTTP** endpoint (Netty + JGit) that proxies a real upstream origin.

```
git-black (blackgitcli.py)                        blackgit server (Netty + JGit)
  ordinary git + smart-http  ──────────────────▶  authenticates (Authorization: Basic)
  partial clone (blob:none)                        │
  sparse-checkout "cared files"                    ├─ read : blob allowlist by path (SVN authz format)
                                                   │         on-demand single-sha backfill from origin
                                                   │         download only needed sha
                                                   └─ write: canPush check + file-lock enforcement
                                                             proxy receive-pack to origin
                                                             replay the same body into the local cache
                                                                       │
                                                                       ▼
                                                                 origin (GitLab / GitHub)
                                                                 client's token forwarded verbatim
```

The client and server are decoupled: client works against **any** git smart-HTTP remote (a bare GitLab, GitHub, or BlackGit itself).

### Authentication & authorization

- **AuthN**: every smart-HTTP request must carry `Authorization: Basic <user:token>`.
The server decodes only the username for its own decisions;
the raw header is forwarded to origin verbatim so upstream authenticates as the same user.
- **AuthZ (read)**: each repo may carry a `blackw-authz` file in standard SVN `authz` format (groups, `@group`, `*`, `r`/`w`).
The user's granted path prefixes become a blob allowlist: **commits and trees are always served** (history and directory navigation keep working),
but a blob whose path is not covered by an `r` grant is refused on the wire.
With no `blackw-authz` file, every blob is downloadable (open mode).
- **AuthZ (write)**: push requires a `w` grant somewhere in `blackw-authz`.
Force-push and ref deletion are rejected by JGit. `--read-only` rejects all pushes server-wide.
- The allowlist is computed over **reachable history** (not just the tip) and cached; it is invalidated after each push.
- **File locks**: `git black lock <file>` records who locked a file server-side. Subsequent pushes touching that file are rejected unless they come from the locker. `git black lock -d <file>` unlocks. A second lock on an already-locked file returns the current locker.

### The client: `git black`

`blackgitcli.py` is a thin wrapper around stock git — it talks ordinary smart-HTTP and never requires the BlackGit server.

| Command | What it does |
|---------|--------------|
| `git black clone <url> [<dir>]` | `git init`, set `origin`, configure partial clone (`blob:none`), fetch `--depth=1`, point HEAD at the remote tip **without** materializing the worktree, arm an empty sparse-checkout (`!/* !/*/*`) |
| `git black follow <path>…` | Add a file/dir to your "cared" set; sparse-checkout materializes exactly those blobs (`-r` recursive, `-d` remove, `-l` list) |
| `git black update` | Fast-forward only: fetch commits with `--filter=tree:0`, move the branch, re-apply the cared view; diverged or dirty worktree falls back to standard `git pull` |
| `git black ls [<ref>|<path>|<branch>:<path>]` | List the tree without materializing blobs; server filters out paths the caller cannot download |
| `git black lock <file>` | Lock a file (only locker can push changes to it) |
| `git black lock -d <file>` | Unlock a file |
| `git black branch` | List local + remote branches with the current branch marked |
| anything else (`push`, `status`, `log`, …) | Passed straight through to stock git, arguments unchanged |

Auth is left to git's standard HTTP layer (credential helper / keychain / `http.extraHeader`); black never parses user info out of the URL. Your cared-file set lives in `.git/blackw-add.tsv` and is purely a **client-side view** (what lands in your worktree). It is independent of the server-side blob allowlist, which is the actual security boundary.

---

## Install

### Client (git black) — pip

```bash
pip install blackgit
```

Then:

```bash
git black clone https://gitlab.example.com/group/repo.git
cd repo
git black follow src/engine        # start caring about a subtree
git black update                   # commits only; trees/blobs on demand
```

### Server — Docker (recommended)

Pre-built images are on GitHub Container Registry:

```bash
docker pull ghcr.io/zhuzhonghua/blackgit:latest
```

Run with env vars (no config file needed):

```bash
docker run -d \
  -p 8081:8081 \
  -v /data/blackgit:/data \
  -e BLACKGIT_PORT=8081 \
  -e BLACKGIT_UPSTREAM=https://github.com/user/repo.git \
  ghcr.io/zhuzhonghua/blackgit:latest
```

Environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `BLACKGIT_PORT` | `8081` | listen port |
| `BLACKGIT_ROOT` | `/data` | repo cache root (mount a volume here) |
| `BLACKGIT_UPSTREAM` | — | upstream URL, auto-bootstraps an empty bare repo on first start |
| `BLACKGIT_READ_ONLY` | off | set to `1` to reject all pushes |
| `BLACKGIT_TLS` | off | set to `1` to enable HTTPS |
| `BLACKGIT_KEYSTORE` | — | path to keystore when TLS=1 |
| `BLACKGIT_KEYSTORE_PASSWORD` | — | keystore password |
| `BLACKGIT_KEY_PASSWORD` | — | key password |

### Server — build from source

```bash
lein uberjar
java -jar target/blackgit-*-standalone.jar \
     --port 8081 --root /path/to/repos \
     [--upstream https://github.com/user/repo.git] \
     [--tls --keystore … --keystore-password …] [--read-only]
```

A URL like `http://host:8081/repo.git` resolves to `<root>/repo`. If `--upstream` is set, the server creates an empty bare repo on first start and lazily fetches refs from upstream on the first client request (same strategy as josh).

---

### Tech stack

- **Server**: Java 21, JGit 6.10, Netty 4.1; built with Leiningen.
- **Client**: Python 3; plain git subprocesses. Git 2.54+ required for partial clone.
- **Transport**: git smart-HTTP over HTTP/1.1 (optionally TLS). Only standard HTTP(S) git is supported; non-HTTP connections are dropped on first bytes.
