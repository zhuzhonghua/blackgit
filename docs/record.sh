#!/bin/bash
# Records docs/hero.gif and docs/hero.mp4 from docs/hero.tape.
#
# Both files are the same recording, encoded twice, and both are a *recorded
# real run*: the real client against the real server on a real 1.4 GB repo.
#
# Each take gets a completely fresh fixture and a fresh server. That is not
# hygiene, it is correctness:
#   * the lock table lives next to the bare repo and survives between takes, so
#     take two would start with take one's lock already held;
#   * a client directory left over from take one makes bob's `checkout -b`
#     fail and `follow` report "already caring";
#   * pushes leave objects behind, so a reused repo grows and the `du` on screen
#     stops matching the "1.4 GB" quoted in the tape and in both READMEs.
#
# Set BG_SKIP_FIXTURE=1 to reuse the existing repo (faster, but then check the
# size assertion below before trusting the recording).
set -euo pipefail

DEMO=/private/var/folders/9p/gnbd_67s0ng9c85fjmk1zp040000gn/T/opencode/bg-demo
REPO="$DEMO/repos/game"
HERE="$(cd "$(dirname "$0")" && pwd)"
SHORT=/tmp/bg

# the size the tape and both READMEs quote
EXPECTED_SIZE=1.4G

SERVER_PID=""
stop_server() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  pkill -f "blackgit-0.1.0-SNAPSHOT-standalone.jar" 2>/dev/null || true
  SERVER_PID=""
  sleep 1
}

# One clean starting point for one take.
prepare_take() {
  stop_server

  if [ "${BG_SKIP_FIXTURE:-0}" != "1" ]; then
    "$DEMO/make-fixture.sh" >/dev/null
  fi
  ACTUAL_SIZE=$(du -sh "$REPO" | cut -f1)
  if [ "$ACTUAL_SIZE" != "$EXPECTED_SIZE" ]; then
    echo "fixture is $ACTUAL_SIZE, but the tape and the READMEs say $EXPECTED_SIZE" >&2
    echo "update all three together, or rebuild: $DEMO/make-fixture.sh" >&2
    exit 1
  fi

  # A short real directory for the client. git prints absolute paths in its own
  # messages ("Initialized empty Git repository in ..."), and the fixture's real
  # path is long enough to wrap every line on screen -- and it has to be a real
  # directory, not a symlink, because git resolves symlinks before printing.
  rm -rf "$SHORT"
  mkdir -p "$SHORT/shots"
  ln -s "$DEMO/repos" "$SHORT/repos"
  ln -s "$DEMO/bin" "$SHORT/bin"
  ln -s "$DEMO/as-user.sh" "$SHORT/as-user.sh"
  chmod +x "$DEMO/as-user.sh" "$DEMO/bin/git-black"

  java -jar "$HERE/../target/blackgit-0.1.0-SNAPSHOT-standalone.jar" \
       --port 8081 --root "$DEMO/repos" > "$DEMO/server.log" 2>&1 &
  SERVER_PID=$!
  trap 'stop_server' EXIT

  for _ in $(seq 1 60); do
    curl -sf "http://localhost:8081/game.git/authz" >/dev/null 2>&1 && break
    sleep 1
  done

  # The tape inherits this environment, so none of it shows up on screen.
  export PATH="$SHORT/bin:$PATH"
  export BLACKGIT_QUIET=1
  export GIT_TERMINAL_PROMPT=0
  . "$DEMO/as-user.sh" alice

  # Warm the server: the first request on a fresh repo pays a one-off pack
  # repack + bitmap build that would otherwise land inside the recording.
  (cd "$SHORT/shots" && git black clone http://localhost:8081/game.git >/dev/null 2>&1) || true
  rm -rf "$SHORT/shots/game"
}

cd "$HERE/.."

# --- take 1: the GIF ------------------------------------------------------
prepare_take
vhs docs/hero.tape

# vhs writes a ~2.5 MB / 48 s GIF. The committed one is re-encoded: this is the
# smallest change that still reads comfortably (980 px, 15 fps, 64 colours ->
# ~1.7 MB). See docs/README-hero.md.
ffmpeg -v error -i docs/hero.gif -filter_complex \
  "[0:v]fps=15,scale=980:-2,split[a][b];[a]palettegen=max_colors=64:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  -loop 0 docs/hero.opt.gif -y
mv docs/hero.opt.gif docs/hero.gif

# --- take 2: the MP4, same tape, Output line rewritten --------------------
prepare_take
sed 's|^Output .*|Output docs/hero.raw.mp4|' docs/hero.tape > "$DEMO/hero-mp4.tape"
vhs "$DEMO/hero-mp4.tape" >/dev/null
rm -f "$DEMO/hero-mp4.tape"

# H.264 / yuv420p so it plays in QuickTime, browsers and phones, with the moov
# atom up front so it starts playing without a full download. No audio track --
# the recording has none, and an empty one only trips some players.
ffmpeg -v error -i docs/hero.raw.mp4 -c:v libx264 -crf 22 -preset slow \
  -pix_fmt yuv420p -profile:v high -level 4.0 -movflags +faststart -an \
  docs/hero.mp4 -y
rm -f docs/hero.raw.mp4

ls -la docs/hero.gif docs/hero.mp4

# The demo's whole point is that bob's push was refused. If a take went wrong
# (wrong credentials, wrong working directory, stale state), the ref lands
# anyway -- so fail loudly rather than shipping a GIF that shows the opposite
# of the claim.
if git -C "$REPO" show-ref --verify --quiet refs/heads/twilight; then
  echo "RECORDING IS WRONG: refs/heads/twilight exists, so the push was NOT refused" >&2
  exit 1
fi
echo "ok: bob's push was refused and left no branch behind"
stop_server