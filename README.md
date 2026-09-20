## BlackGit

BlackGit sits in front of a standard Git server (GitLab/GitHub) as a smart-HTTP cache and access-control layer. You keep using ordinary `git fetch` / `git push` / `git checkout`,
while BlackGit controls what objects a client may download, keeps a shallow on-demand cache, and enforces per-path permissions on top of the upstream remote.

---

### How it works

BlackGit is composed by client and server sides;

BlackGit Server is a standard **git smart-HTTP** endpoint (Netty + JGit) that proxies a real upstream origin.
Two pieces make it useful for large, permission-sensitive repos:

- **Partial clone + sparse, per-user views on the client** (`blackw`, Python) — wraps stock git:
clone with `blob:none`, then materialize only the files the user explicitly `follow`s.
No LFS, no full history of a multi-GB repo.
- **A shallow cache with path-level blob authorization on the server** —
the server serves a (possibly shallow) local copy of the upstream repo,
pulls any missing object from origin on demand at single-sha granularity,
and refuses to hand out blobs that fall outside the caller's authorized paths.

```
git-black (blackgitcli.py)                        blackgit server (Netty + JGit)
  ordinary git + smart-http  ──────────────────▶  authenticates (Authorization: Basic)
  partial clone (blob:none)                        │
  sparse-checkout "cared files"                    ├─ read : shallow cache on disk
                                                   │         on-demand single-sha backfill from origin
                                                   │         blob allowlist by path (SVN authz format)
                                                   └─ write: canPush check + file-lock enforcement
                                                             proxy receive-pack to origin
                                                             replay the same body into the local cache
    │
    ▼
                                                                 origin (GitLab / GitHub)
                                                                 client's token forwarded verbatim
```

The client and server are decoupled: `git black clone <url>` works against **any** git smart-HTTP remote (a bare GitLab, GitHub, or BlackGit itself).
Pointing the remote at BlackGit is what turns on caching and permissions.

### Data flow

```mermaid
sequenceDiagram
    participant C as blackw (client)
    participant S as BlackGit server
    participant O as origin (GitLab/GitHub)

    Note over C,S: clone / fetch
    C->>S: GET /repo.git/info/refs?service=git-upload-pack
    S-->>C: advertised refs (from shallow local cache)
    C->>S: POST git-upload-pack (wants + filter blob:none)
    S->>S: pre-parse wants; backfill any missing sha from origin
    S-->>C: pack (only allowlisted blobs)

    Note over C,S: lazy blob on demand
    C->>S: POST git-upload-pack (wants <blob-sha>)
    S->>O: fetch +<sha> via temp ref (keeps shallow boundary)
    O-->>S: just the missing object graph
    alt blob path not in allowlist
        S--xC: error: blob not authorized
    else
        S-->>C: pack
    end

    Note over C,S: push
    C->>S: POST git-receive-pack (with Authorization: Basic)
    S->>S: canPush(user)? reject otherwise
    S->>S: file-lock check: non-holder push on locked file → 403
    S->>O: forward same body, token passed through
    O-->>S: result
    S->>S: replay body into local cache (no extra round-trip)
    S-->>C: result
```

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

Auth is left to git's standard HTTP layer (credential helper / keychain / `http.extraHeader`); blackw never parses user info out of the URL. Your cared-file set lives in `.git/blackw-add.tsv` and is purely a **client-side view** (what lands in your worktree). It is independent of the server-side blob allowlist, which is the actual security boundary.

---

## Install

### Client (git black) — pip

```bash
pip install blackgitcli
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
docker pull ghcr.io/<your-org>/blackgit:latest
```

Run with env vars (no config file needed):

```bash
docker run -d \
  -p 8081:8081 \
  -v /data/blackgit:/data \
  -e BLACKGIT_PORT=8081 \
  -e BLACKGIT_UPSTREAM=https://github.com/user/repo.git \
  ghcr.io/<your-org>/blackgit:latest
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

## Release

### Prerequisites (one-time)

1. **PyPI token** — create at https://pypi.org/manage/account/token/ (scope: "Entire account" or specific project).
2. **GitHub Secrets** — add the token as a repo secret:
   - Go to your repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**
   - Name: `PYPI_API_TOKEN`, Value: the PyPI token.
3. **GitHub Container Registry** — no extra setup needed. The workflow uses the built-in `GITHUB_TOKEN`, which can push to `ghcr.io`. If the first push fails, enable packages in repo **Settings → Actions → General → Workflow permissions → Read and write permissions**.

### Cut a release

Everything is automated by GitHub Actions on tag push:

```bash
# 1. Make sure everything is committed and pushed to main
git status
git push origin main

# 2. Tag and push — this triggers both workflows
git tag v0.1.0
git push origin v0.1.0
```

GitHub Actions will:

| Workflow | What it does | Artifact |
|----------|--------------|----------|
| `.github/workflows/docker.yml` | `lein uberjar` → `docker build` → push to `ghcr.io` | `ghcr.io/<org>/blackgit:latest` and `:v0.1.0` |
| `.github/workflows/pypi.yml` | `python -m build` → `pypi-publish` | `blackgitcli 0.1.0` on PyPI |

After the workflows finish (check the **Actions** tab), users can:

```bash
pip install blackgitcli            # client
docker pull ghcr.io/<org>/blackgit # server
```

### Local build test (before tagging)

```bash
# server jar
lein uberjar

# Docker image
docker build -t blackgit:local .

# pip package (dry-run)
pip install build
python -m build
pip install dist/blackgitcli-0.1.0-py3-none-any.whl --force-reinstall
git black --help
```

---

### Tech stack

- **Server**: Java 21, JGit 6.10, Netty 4.1; built with Leiningen.
- **Client**: Python 3; plain git subprocesses. Git 2.54+ required for partial clone.
- **Transport**: git smart-HTTP over HTTP/1.1 (optionally TLS). Only standard HTTP(S) git is supported; non-HTTP connections are dropped on first bytes.

### Status

Implemented: clone/fetch with partial clone, on-demand single-sha backfill (cache stays shallow), path-based blob authorization via `blackw-authz`, push proxy with local-cache replay and self-healing, file locks, `git black ls` with server-side permission filtering, auto-bootstrap from upstream URL (lazy fetch), Docker image, pip package, GitHub Actions CI/CD.

Roadmap: multi-repo dashboard, GUI, SSH upstream (deferred — conflicts with identity forwarding).
