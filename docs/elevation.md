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

1. **Keep the height** - store it with an asset's points, carry it in a backup, so a track that came
   in with heights still has them tomorrow, and a GPX exported back out is not missing its `<ele>`.
2. **Say the range on the track's card** - one line, *"≈520–664 m, up 140 m"*, so a track that climbs
   is visible without opening a chart.
3. **An elevation profile** - a small chart of height along the line, on the track's own page and on
   the recorder's after-save screen, where the two places a height is worth reading actually are.

## The plan

- [ ] **Store the height with a track's points.**
      `asset_points` gains a nullable `altitudeM` column - schema **v12** (`MIGRATION_11_12`) - and
      `AssetPointEntity` and `AssetRepository` write and read it, so `GeoPoint` and `AssetGeometry`
      keep height in memory the way they keep lat and lng. The import path already builds points
      *with* height, so this is the one break in the chain.
- [ ] **Carry the height in a backup.**
      `LinePointRecord` gains a nullable `altitudeM`, and `BackupRepository` writes and reads it. An
      absent key reads as no height, and a file written after this is read by a build before it as
      before (the reader already ignores keys it does not know). Proved by a round trip.
- [ ] **Work the numbers out once, in a place a test can hold.**
      A pure `ElevationProfile` in the domain: the lowest and highest point, the total climb and
      descent, and the heights brought to a line for drawing. It takes the path and nothing else, so
      it is held by a JVM test and the phone and any later reader cannot disagree about it.
- [ ] **Say the range on the card.**
      `AssetDetailScreen` gains one line when, and only when, the geometry has heights: the lowest to
      the highest, and the climb. A drawn track says nothing - not "0 m", which would be a
      measurement where there is only an absence.
- [ ] **Draw the profile on the track's page.**
      A small composable under the card's facts: the line's height along its own length. It is the
      **line** (path 0), because that is the ground the job is driven along; a side track hanging off
      it is read as part of the line's run unless the operator asks otherwise later.
- [ ] **Draw the profile of the pass just driven, after Save.**
      The recorder already stores a height on every fix, so this needs no migration - only a read.
      It is the same chart, over the recording's own points, on the screen the recorder keeps after
      a pass is saved ("the screen a second after Save").
- [ ] **A GPX written back out keeps its heights.**
      Nothing to build: `GpxWriter` already writes `<ele>` when a point has one, so this follows from
      the first item. It is worth a test, because until now an imported-then-exported track lost its
      heights and the export was quietly poorer than the import.

Tick a box and add a line under it saying **how it was proved**, so this section says where the work
stopped rather than only what was meant.

## The one decision not yet made

**A height moved on the desk.** The desk draws a track and hands its line back as `lat` and `lng`
only, so a vertex the operator drags on a computer arrives with no height, and a desk edit of an
imported track would flatten the heights of the whole line. The phone's own drawing screen is not
like this: it edits the very `GeoPoint`s it opened, so an untouched vertex keeps its height and only
the one that was dragged loses it.

There are two ways through, and the answer changes how much is built:

- **The phone is enough.** Accept that a desk edit flattens a line's heights, and say so where the
  desk is described. The card's range and both profiles are the phone's, worked out from what the
  phone holds. This is the smaller change and what the first plan assumes.
- **The desk keeps them too.** The point on the wire (`WebEditorPoint`) gains a nullable `altitudeM`,
  the writer sends the phone's heights out and takes them back, and the version fingerprint
  (`WebEditorVersion`) folds altitude into the point it already hashes - otherwise a vertex that
  moved only in height would be a write the card could not see. The desk can then show the profile
  as well. It is a wire change and a fingerprint change, and it makes the desk equal to the phone.

This is asked of the operator rather than assumed, because "does the computer need to keep the
height" is a question about how the work is done (drawn on the desk, or only ever imported), not
about the code.

## Files

| File | What it is here |
| --- | --- |
| `data/db/AssetPointEntity.kt` | The asset's points: gains a nullable `altitudeM`. |
| `data/db/Migrations.kt`, `SprayDayDatabase.kt` | `MIGRATION_11_12` (an `ALTER TABLE assets ADD COLUMN`), schema v12, `12.json` written out. |
| `data/AssetRepository.kt` | Writes the height with each point and reads it back, so `GeoPoint.altitudeM` survives a save and a load. |
| `domain/backup/BackupDocument.kt`, `data/BackupRepository.kt` | `LinePointRecord` gains `altitudeM`, and the backup carries it out and in. |
| `domain/geo/ElevationProfile.kt` (new) | The arithmetic: lowest, highest, climb, descent, and a series to draw. Pure, tested. |
| `ui/screens/AssetDetailScreen.kt` | The card's range line and the profile of the track. |
| `ui/screens/RecordScreen.kt` | The profile of the pass just saved. |
| `domain/track/GpxWriter.kt` | Unchanged - it already writes `<ele>`; this is where a stored height reaches a file. |

## What holds it

- `ElevationProfile` held by a JVM test: a climbing path, a flat one, one with a missing height in
  the middle, and one with too few heights to say anything.
- A round trip through the repository: a track saved with heights is read back with the same
  heights, and one saved without comes back with none.
- A backup written before the change restores unchanged, and one written after it keeps the heights -
  both directions, because a backup is the copy that has to outlive the build.
- `MIGRATION_11_12` against a populated v11 database, on the emulator, like every migration.
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
