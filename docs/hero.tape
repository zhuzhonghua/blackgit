# blackgit hero recording — the README demo.
#
# Everything on screen is a real run: a real 1.4 GB bare repo, the real
# server, the real client. No number is typed in by hand.
#
#   ../make-fixture.sh   build the 1.4 GB game repo (real random blobs)
#   ./record.sh          reset state, warm the server, run vhs
#
# Setup that must not appear on screen lives in record.sh's environment.
# The tape works through /tmp/bg: a real short directory holding the fixture
# behind symlinks, so git's own messages do not print a 90-character path.

Output docs/hero.gif

Set Shell "bash"
Set FontSize 15
Set FontFamily "Menlo, Monaco, DejaVu Sans Mono, monospace"
Set Width 1180
Set Height 640
Set Padding 20
Set Theme "Catppuccin Frappe"
Set TypingSpeed 25ms
Set PlaybackSpeed 1.15

Hide
Type "cd /tmp/bg/shots && clear"
Enter
Show

# --- beat 1: the repo nobody wants to download --------------------------
Type "# the game repo on the server"
Enter
Sleep 700ms
Type "du -sh /tmp/bg/repos/game"
Enter
Sleep 1800ms
Type "# your laptop takes the commits, not the 1.4 GB"
Enter
Sleep 700ms
Type "git black clone http://localhost:8081/game.git"
Enter
Sleep 2000ms
Type "cd game"
Enter
Sleep 400ms
Type "du -sh .git"
Enter
Sleep 2200ms

# --- beat 2: browse everything, download nothing ------------------------
Type "# the whole tree, browsable with zero bytes fetched"
Enter
Sleep 700ms
Type "git black ls assets/levels"
Enter
Sleep 1800ms

# --- beat 3: take exactly the file you are working on --------------------
Type "# take the one file you actually work on"
Enter
Sleep 700ms
Type "git black follow assets/levels/city.umap"
Enter
Sleep 2600ms
Type "du -sh .git"
Enter
Sleep 1800ms

# --- beat 4: alice locks it. same reflex as p4 edit ----------------------
Type "# alice checks the level out, like p4 edit"
Enter
Sleep 700ms
Type "git black lock assets/levels/city.umap"
Enter
Sleep 2000ms

# --- beat 5: bob cannot, and he is told exactly why ---------------------
Type "source ../../as-user.sh bob"
Enter
Sleep 400ms
Type "# bob asks for the same level"
Enter
Sleep 700ms
Type "git black lock assets/levels/city.umap"
Enter
Sleep 3000ms

# --- beat 6: ...but the lock does not touch his local work --------------
Type "# nothing stops bob working locally"
Enter
Sleep 700ms
Type "git checkout -b twilight"
Enter
Sleep 1300ms
Type "echo x >> assets/levels/city.umap"
Enter
Sleep 1300ms
Type "git commit -qam 'move the bridge'"
Enter
Sleep 2000ms

# --- beat 7: only the push is refused, and it names the holder -----------
Type "# the lock stops the push, and names the holder"
Enter
Sleep 700ms
Type "git push origin twilight"
Enter
Sleep 4000ms

# --- the point of the whole thing ----------------------------------------
Type "# the lock guards the push, never your branch."
Enter
Sleep 4500ms