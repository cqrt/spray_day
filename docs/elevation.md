# Elevation

A track file usually carries the height of every point it holds, and the app currently reads that
height and throws it away the moment a track is saved. This is the plan to keep it and to show it,
and it is **not built yet**.

## What the data is

A height is one number per point, in metres above sea level:

| A file | Where the height sits |
| --- | --- |
| **GPX** | `<ele>` inside the point's `<trkpt>` |
| **KML** | the third number of `lon,lat,alt` in `<coordinates>`, or of `lon lat alt` in `<gx:coord>` |
| **GeoJSON** | the third number of a position, `[lon, lat, alt]` |

It is the height the device happened to record - barometric, or corrected against a terrain model
by whatever wrote the file - and **not a survey**. It is good enough to say that a line climbs and
roughly by how much, and not good enough to put a grade in a record somebody is audited against.

A file also commonly carries other things beside the height: the attached `Kiwi_Burn_Morning_Hike.gpx`
has a timestamp and a heart rate on every point (Strava's export). This plan is about the height
alone; a heart rate is not the app's business and is left where it is.

## Where it stands today

The app's own model already knows about height, and the readers already fill it in - the loss is at
the first table write:

- **In memory it is kept.** `GeoPoint` carries `altitudeM: Double?`, and the GPX, KML and GeoJSON
  readers all put the file's height into it. The GPX writer puts `<ele>` back out when it is present.
- **In the database it is dropped.** An asset's geometry lives in `asset_points`, which has **only
  `lat` and `lng`**, so `AssetRepository` writes the two numbers and nothing else. The backup's line
  points (`LinePointRecord`) are the same two numbers, and so is the point the desk is handed over
  the wire (`WebEditorPoint`).
- **A recording keeps it, and nothing reads it.** Each GPS fix stores its altitude
  (`recorded_points.altitudeM`, in the backup too) - but no screen anywhere shows a height.

So the shape of it today is: a file's heights are read, and then an imported track loses them; a
recorded pass keeps them, and they are never looked at.

## What the feature is

Three things, in the order they depend on each other:

1. **Keep the height** - store it with an asset's points, carry it in a backup and over the wire, so a
   track that came in with heights still has them tomorrow, on the desk as well as the phone, and a
   GPX exported back out is not missing its `<ele>`.
2. **Say the range on the track's card** - one line, *"≈520–664 m, up 140 m"*, so a track that climbs
   is visible without opening a chart.
3. **An elevation profile** - a small chart of height along the line, on the track's own page (on the
   phone and on the desk) and on the recorder's after-save screen, where the places a height is worth
   reading actually are.

## The plan

- [x] **Store the height with a track's points.**
      `asset_points` gains a nullable `altitudeM` column - schema **v12** (`MIGRATION_11_12`) - and
      `AssetPointEntity` and `AssetRepository` write and read it, so `GeoPoint` and `AssetGeometry`
      keep height in memory the way they keep lat and lng. The import path already builds points
      *with* height, so this is the one break in the chain.
      Proved by a file imported with heights coming back with them, and a GPX exported again still
      carrying `<ele>` (`AssetAndSprayDataTest`). The migration test is written against a populated
      v11 database; it runs on an emulator, so it is not proved on this machine.
- [x] **Carry the height in a backup.**
      `LinePointRecord` gains a nullable `altitudeM`, and `BackupRepository` writes and reads it. An
      absent key reads as no height, and a file written after this is read by a build before it as
      before (the reader already ignores keys it does not know). Proved by a round trip, and by a
      file with the key stripped reading as no heights (`BackupFormatTest`).
- [x] **Work the numbers out once, in a place a test can hold.**
      A pure `ElevationProfile` in the domain: the lowest and highest point, the total climb and
      descent, and the heights brought to a line for drawing. It takes the path and nothing else, so
      it is held by a JVM test and the phone and any later reader cannot disagree about it. Proved by
      `ElevationProfileTest` - a climb and a return, a flat run, a height missing in the middle, and a
      line with too few heights to say anything - and `ElevationPhrase` beside it for the words.
- [x] **Say the range on the card.**
      `AssetDetailScreen` gains one line when, and only when, the geometry has heights: the lowest to
      the highest, and the climb. A drawn track says nothing - not "0 m", which would be a
      measurement where there is only an absence. Proved by the words themselves in
      `ElevationProfileTest`; not yet looked at on a screen, because no emulator was attached.
- [x] **Draw the profile on the track's page.**
      A small composable under the card's facts: the line's height along its own length. It is the
      **line** (path 0), because that is the ground the job is driven along; a side track hanging off
      it is read as part of the line's run unless the operator asks otherwise later. `ElevationChart`
      is the one drawing, shared with the recorder. Built, but not yet looked at on a screen.
- [x] **Draw the profile of the pass just driven, after Save.**
      The recorder already stores a height on every fix, so this needs no migration - only a read.
      It is the same chart, over the recording's own points, on the screen the recorder keeps after
      a pass is saved ("the screen a second after Save"). The saved pass carries its fixes
      (`FinishedPass.recorded`), so the chart is drawn from them; built, not yet looked at.
- [x] **A GPX written back out keeps its heights.**
      Nothing to build: `GpxWriter` already writes `<ele>` when a point has one, so this follows from
      the first item. It is worth a test, because until now an imported-then-exported track lost its
      heights and the export was quietly poorer than the import. Proved by the same import test
      exporting the track it just read and finding the height in the file.
- [ ] **Carry the height on the wire, and fold it into the fingerprint.**
      `WebEditorPoint` gains a nullable `altitudeM`; `WebEditorJson` sends it on every point of the
      record's paths (and in the answer to a dropped file), and `WebEditorEdit` reads it back into the
      `GeoPoint` a drawing becomes. `WebEditorVersion` hashes it with the point, so a vertex that moved
      only in height is a change the card can see rather than a write taken over a card that never
      saw it.
- [ ] **Keep the heights through the desk's own drawing.**
      The page's points become `lat`, `lng` and an optional height. A click or a drag makes a point
      with no height - a tap on a map has none - while every vertex the operator did not touch keeps
      its own; undo, redo, a side track and a trace carry whichever heights their points have, because
      to the page it is one more number on a point rather than a thing to know about.
- [ ] **Show the range and the profile on the desk too.**
      The record carries the phone's lowest, highest and climb, so the desk's card says the same line
      the phone's does; and the page draws the profile from the heights it already holds. The
      arithmetic stays the phone's and the desk's is the drawing - the rule the area's own figures
      already keep, so the two surfaces cannot disagree about a number.

Tick a box and add a line under it saying **how it was proved**, so this section says where the work
stopped rather than only what was meant.

## Decided: the desk keeps them too

Asked of the operator rather than assumed, and the answer was the desk as well as the phone - so a
height travels the whole way and neither surface is the poorer one. What that settles, and why:

- **The point on the wire gains a nullable height.** `WebEditorPoint` carries `altitudeM` out in the
  state document the drawing is read from, and reads it back on the write that saves. A page that
  has no height for a point omits it, and an older page that sends none is read as it always was.
- **The version fingerprint folds the height in.** `WebEditorVersion` hashes each point as `lat,lng`;
  it becomes `lat,lng,alt`, empty where there is none. Without this, a vertex that moved only in
  height would hash the same as one that did not, and the phone would take a write over a card it
  should have refused - the exact fault the fingerprint exists to catch.
- **The desk's drawing keeps what it was handed.** A click and a drag make a point with no height,
  because a tap on a map has none, and every vertex the operator did not move keeps its own. This is
  already how the phone's drawing screen behaves, so the two drawings now mean the same thing about
  a height.
- **The desk shows the same range and the same profile.** The record carries the three figures
  (`ElevationProfile`'s lowest, highest and climb), and the page draws the shape from the heights it
  is already handed. Worked out once, on the phone, read everywhere - so a number cannot differ
  between a card and the page that mirrors it.

What does not change: an imported file has heights, a point drawn with a click has none, and a
recording has had them all along.

## Files

| File | What it is here |
| --- | --- |
| `data/db/AssetPointEntity.kt` | The asset's points: gains a nullable `altitudeM`. |
| `data/db/Migrations.kt`, `SprayDayDatabase.kt` | `MIGRATION_11_12` (an `ALTER TABLE asset_points ADD COLUMN altitudeM REAL`), schema v12, `12.json` written out. |
| `data/AssetRepository.kt` | Writes the height with each point and reads it back, so `GeoPoint.altitudeM` survives a save and a load. |
| `domain/backup/BackupDocument.kt`, `data/BackupRepository.kt` | `LinePointRecord` gains `altitudeM`, and the backup carries it out and in. |
| `domain/geo/ElevationProfile.kt` (new) | The arithmetic: lowest, highest, climb, descent, and a series to draw. Pure, tested. |
| `ui/screens/AssetDetailScreen.kt` | The card's range line and the profile of the track. |
| `ui/screens/RecordScreen.kt` | The profile of the pass just saved. |
| `web/WebEditorEdit.kt` | `WebEditorPoint` gains a nullable height, read back into the `GeoPoint` a drawing becomes; `WebEditorVersion` hashes it with the point. |
| `web/WebEditorJson.kt` | The record's paths carry the height out, in the state document and in the answer to a dropped file. |
| `assets/web/geometry.mjs`, `edit.js`, `wire.mjs` | The desk's points keep a height, and a save carries it back with the line. |
| `assets/web/app.js`, `index.html`, `style.css` | The desk's card range line and the profile chart. |
| `domain/track/GpxWriter.kt` | Unchanged - it already writes `<ele>`; this is where a stored height reaches a file. |

## What holds it

- `ElevationProfile` held by a JVM test: a climbing path, a flat one, one with a missing height in
  the middle, and one with too few heights to say anything.
- A round trip through the repository: a track saved with heights is read back with the same
  heights, and one saved without comes back with none.
- A backup written before the change restores unchanged, and one written after it keeps the heights -
  both directions, because a backup is the copy that has to outlive the build.
- `MIGRATION_11_12` against a populated v11 database, on the emulator, like every migration.
- The wire both ways: a record's point carries its height out of the state document, a write with a
  height reads it back into the geometry, and a card whose vertex moved only in height is refused as
  stale (`WebEditorJsonTest`, `WebEditorEditTest`).
- The page's own drawing, under node: a vertex the operator did not touch keeps the height it was
  handed, and a point made by a click has none.
- An imported file whose heights were kept exports to a GPX that still has them.

## Notes and limits

- **The height is the device's, not the ground's.** A file from a phone in a pocket is a few metres
  out and can be tens of metres out under trees; a barometric height drifts with the weather. This is
  why it is shown as a range and a shape, never as a surveyed number.
- **A point the operator drew has no height**, and a track of drawn points shows no range and no
  profile. That is an absence in the data, said as nothing rather than as a zero.
- **What it is not for:** the swath area, the lengths, the coverage and the two-pass reading are all
  on the flat, and this does not change them. A height is something to read about a track, not
  something any of the app's other arithmetic is worked out from.
