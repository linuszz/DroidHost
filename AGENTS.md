# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.

# This repository

- DroidHost is a fork of `Droid-Deck/DroidDeck` (GPL-3.0). Keep upstream's copyright, attribution and licence notices intact.
- The Android package is `com.droidhost.launcher`. Keep it in step with `namespace` in `app/build.gradle`, `tools/release/variants.txt` and the JNI symbols in `app/src/main/cpp` and `app/src/main/rust`.
- The `droiddeck-*` guest runtime names, the `droiddeck-*` Vulkan layer names' file names and the `Droid-Deck/*` upstream repository URLs are not ours to rename. See `docs/droidhost.md` for the full list of what is deliberately kept.
- `upstream` points at `Droid-Deck/DroidDeck`. Pull from it for fixes; never rewrite its history.

# Working on the device

- `dd-lab-build` builds only committed work. Check `git status` and `gh api user` for the identity before committing; if `git config user.name` is empty, use the `gh` login's name and noreply email for this repo, never a guess.
- Do not commit or start a build in the same step: a build started next to a failed commit fails too. Commit first, check it, then build.
- Uncommitted changes in the tree may not be yours. Read `git diff` before committing them and tell the user what they do.
- If `dd-lab-build` says "could not read DroidHost's state", loopback adb is down and `$ADB_SERIAL` is stale. Run `adb devices`; the port changes whenever wireless debugging is toggled. Ask the user to turn it on rather than retrying.
- A build that "did not start" can be adb, not the app. Read `~/.cache/dd-lab-build.json` and `.log` for the real reason; they can be stale from an earlier build, so compare their times with `date`.
- Run `dd-lab-build` with `run_in_background` and read its output file. A foreground `sleep` is blocked; wait with Monitor and an until-loop.
- The build restarts DroidHost and ends your process. A turn that ends this way is not a failure.
