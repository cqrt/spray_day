# Releasing and verifying a change

How work leaves this machine. `project.md` is the project itself; this is the gate it has to pass.

## Release rules (README §Release — not optional)

- A **code** change is committed, pushed and **tagged as its own version** before anything else
  is started. Take the next version in the sequence — patch + 1 — and never reuse a published
  number. The newest tag is `git tag --sort=-v:refname | Select-Object -First 1`; the next
  version is that plus one.
- **Documentation alone is not a release**: commit and push it **untagged**; it rides into the
  next code release's tag.
- One change, one commit. The message says what changed and why, in the app's own voice.

```bash
git add -A && git commit -m "..." && git push origin main      # code: tag it
git tag -a v0.X.Y -m "..." && git push origin v0.X.Y           # ships the release
```

## Gates — all of these before a code change is committed

1. `./gradlew assembleDebug testDebugUnitTest lint` — lint clean apart from the one known
   pre-existing RecordScreen warning.
2. `./gradlew connectedDebugAndroidTest` when the change touches the database, a repository, a
   view model, or anything a JVM unit test cannot reach.
3. The change verified **on the emulator by screenshot**, not by reasoning.
4. The **published** APK re-verified when a release has gone out: the shipped artifact is
   minified, so the debug build passing is not the same claim.

## How verification is done here — use these, don't invent new ones

- Emulator `Medium_Phone_API_36.0` (1080x2400, `emulator-5554`); app id `nz.mckenzie.sprayday`;
  database `databases/spray_day.db`.
- Scratch scripts under `build/verify` (gitignored, and never to be listed wholesale):
  - `ui.ps1 -Action texts | tap -Text <regex> | tapxy -Text "x,y" | type | clearfield | scroll |
    shot | size` — drive the app through its own UI tree.
  - `db/shot.ps1 -Name <name>` — screenshot plus a readable `-small.jpg` beside it.
  - `db/shotdiff.ps1 -Before a.png -After b.png` — per-row pixel diff, for "did this change only
    the thing it should have".
  - `db/linepix.ps1`, `db/placepix.ps1`, `db/redcount.ps1`, `db/measure-shot.ps1` — counts within
    a band or a box, for colours, icon sizes and dashes.
  - `db/seed*.py` — build a seeded `spray_day.db` from one pulled off the emulator. Push it back
    with `adb push` + `run-as nz.mckenzie.sprayday`; that only works on a debuggable build, so a
    released APK's database cannot be pulled.
  - Notes for finished work go in `build/verify/<feature>.txt`: prose, with `----` underlined
    section headings. Commit messages are drafted in `build/verify/commit-*.txt` first.
- Prove a claim with pixels, a JSON payload or a query result. "It should work now" is not a
  verification.
