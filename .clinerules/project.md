# Spray Day — project rules

An Android app for planning spray tracks and keeping spray records: Kotlin + Jetpack Compose,
Room, DataStore, WorkManager, MapLibre Android, kotlinx-serialization. Public repository
`cqrt/spray_day`. The off-site backups live in the private `cqrt/spray-day-backups`.

Rules are split by what they govern: this file is the project itself, and
`release-and-verification.md` is how a change gets out the door. Both are always active.

## Read first

- `README.md` — what the app does and why. It is documentation, not a changelog; it is the
  place a decision is written down once the feature that made it has shipped.
- `docs/` — plans and handovers for work in progress. If a plan document exists for the thing
  being asked for, read it before asking anything, and check the state it claims rather than
  trusting it.

## House style

- Comments explain **why**, never what the line does. A decision worth arguing about gets a
  paragraph saying what the alternatives were.
- Prefer a **table, or an enum with a test that pins the mapping**, over a `when` buried in a
  view. See `map/AssetLayerIds.kt` and its test: a mapping a test can pin cannot drift.
- A default must leave an untouched install behaving exactly as it did before the feature existed.
- Never a quiet destructive write. Anything that replaces or deletes says what it will do, in
  numbers, before it does it.
- Text the operator reads is plain farm English, not developer English.

## Invariants not to break

- **The backup file has one writer.** `GitHubBackupTarget.save` PUTs the whole document under
  `spray-day-<model>.json`, so anything a second writer put in that file is destroyed by the next
  backup, silently.
- **Deleting an asset cascades into its sprays** (`SprayEventEntity`, `onDelete = CASCADE`);
  recordings survive (`RecordedSessionEntity.assetId` has no foreign key). Never make that easy by
  accident.
- **The tile server is loopback-only by design** (`offline/LocalTileServer.kt`), and
  `TileServerHolder` is the only thing that starts it.
- An empty database is never written over the off-site copy (`domain/backup/OffsiteBackupRules.kt`).
- The LINZ key and the GitHub token are credentials: never in a backup, never in a screenshot,
  never in the repository. `local.properties` and `keystore.properties` are gitignored.

## Don't

- Don't read or list `build/`, `dist/` or `build/verify/` wholesale — hundreds of MB of
  screenshots, logs, APKs and XML dumps. Read the one file you need.
- Don't read a long Kotlin file end to end when a range will do (`BasemapView.kt` is 28 KB,
  `SettingsScreen.kt` 38 KB).
- Don't restate the README or a plan document in chat; point at the section.
- Don't add a dependency without saying in the commit message what it buys and its licence.
- Don't run `./gradlew clean` — it deletes `build/verify`, where the notes and the evidence live.
