# Side tracks

An asset's geometry is **a line, and the side tracks hanging off it** (path 0 is the line; path 1 and
up are side tracks in the order they were drawn). This is the working document for that change; the
behaviour it settled is in `README.md` §A track with a side track, and the verification is in
`build/verify/side-tracks.txt`.

## Why it was not a button

Geometry was one ordered list of vertices (`asset_points` unique on `(assetId, sequence)`), and
"distance along the line" is load-bearing all the way down: `polylineLengthMeters`, the coverage walk,
`TwoPasses`, the map's stretches, the backup, the desk's wire format and version fingerprint, and the
drawing screen's draft. A spur drawn as part of the line therefore cost twice - the length counted it
twice, and the two-pass reading called a dead end *both sides done* because opposite directions over
the same metres is what "two sides" used to mean.

## Shipped: v0.6.27 - the model, the drawing, the numbers

- `asset_points.pathIndex` (default 0) and the unique index becomes `(assetId, pathIndex, sequence)`;
  migration 5 → 6. Every existing track is exactly the line it was.
- `AssetGeometry` (domain/geo) is the currency: `line`, `sideTracks`, `paths`, `lengthM` (each path
  counted once), `pointCount`, `isLine`, `hasSideTracks`.
- `AssetPathEdits.applyPaths` judges a track: a line of two or more, each side track of two or more
  starting **exactly** on a vertex of the line, and the 2000-point cap over the whole track. The
  one-line `apply` is the desk's door into it.
- Repository, DAO and backup speak geometry (the backup's `AssetRecord.spurs`, one path list per side
  track, absent in old files, ignored by old builds).
- Coverage per path (`Coverage.coveredFraction`/`splitByCoverage` over paths, `TwoPasses.splitPaths`),
  the map draws one feature per path (`AssetLine.sideTracks`), the hit test takes the nearest path,
  GPX writes one `<trkseg>` per path.
- The drawing screen: **Side track** / **Back to the track**, the junction being where the line
  currently ends, undo taking the side track with the junction tap, and a half-drawn side track
  dropped rather than refused on save.
- The desk: the version fingerprint covers every path with its boundaries marked, and a PUT that
  carries a line is refused on a track with side tracks (the wire has no field for them yet).

## Shipped: v0.6.28 - changing a track that is already drawn

The phone could draw a new track and nothing else, so a track drawn months ago could not be given a
side track, and a track drawn the old way (doubled line, overstated length) could not be re-drawn -
which the v0.6.27 note promised as the fix.

- **Change the line** on a track's own page opens the drawing screen on its stored geometry. The
  camera opens on the track rather than the phone (the operator is usually nowhere near it); the name,
  kind and block are not on that screen, because they are the details form's; Save is *Save changes*
  and writes the whole geometry with `replaceGeometry`, so the vertices and the length go in together.
  Back without saving writes nothing.
- **The dead-end rule** that v0.6.27 left open. A job with two sides is a *line*; a side track is a
  strip you drive up and back in one trip, so it is never the two-pass reading: on the map
  (`AssetCoverageStretches.of`) each side track gets a job of one pass, and on the card
  (`TwoPasses.splitPaths`) a side track counts as done when **every stretch of it** carries a date -
  because the junction is shared with the line, so "any part covered" would call a spur driven the
  moment a pass went by its mouth.
- The wording decision: buttons say **Side track**, code says spur/`pathIndex`. (Alternatives
  considered and dropped: *tangent*, *branch*, *arm* - none of them is what an operator calls it, and
  "side track" is what the job sheet says.)

## Shipped: v0.6.30 - GPX carries a track with side tracks, both ways

Out was already right - one `<trkseg>` per path, which is what a GPX track's segments are for - but it
had never been checked on a device or on a shipped APK, and reading still flattened every `trkpt` in a
file into one line. So a file exported for a track with a spur came back as a line with a jump in it.

- **The reader reads segments** (`GpxParser.parseSegments`), in the order the file has them, and drops
  a `<trkseg>` with nothing in it. A file that never says where one segment ends - every point loose
  under its `<trk>` - is one path, which is what the app has always read those as. `parse` is now the
  flattened form of the same walk, so nothing about the old behaviour moved.
- **An import decides what the file is**: if every segment after the first starts **exactly** on a vertex
  of the first, the file becomes one track with that many side tracks (the rule the app's own drawing
  keeps, applied to a file). Otherwise it is read the old way - one line, every point - and the answer
  says so, because a track with a jump in it is worth knowing about rather than worth refusing.
- **The sentence says which**: `Imported "x" with 5 points: the line and 1 side track.`, or
  `... as one line: the file's own track segments do not meet, so they were joined up.`

## Next

### v0.6.31 - the desk draws side tracks

Start a side track from a vertex of the traced line on the desk, and take one off again. The wire
carries them (v0.6.29) and the phone's rules judge them; what is missing is the page's own state holding
more than one path - `geometry.mjs` and `edit.js` are single-path today, and their 26 node tests are the
shape that changes with them. Phase 3 of `web-editor.md` also still has GPX drag-and-drop and working on
more than one asset at once.

## Shipped: v0.6.29 - the drawer carries the paths, so the line can be changed from a computer

v0.6.27 shipped side tracks with one refusal standing: a line write from the browser carried a single
path, so on a track that had a spur it would have dropped it, and the phone refused it outright. The
desk can now carry them.

- **The wire has two shapes for a line**: `points`, one path, and `paths`, the line and its side tracks
  after it. A body carrying both is refused as a page that is confused with itself.
- **The record carries the paths** (`WebEditorAssetRecord.paths`, line first) beside the version. The
  geometry a page *draws* still comes from the GeoJSON, one feature per path - but a page cannot hand
  back what it only ever saw as drawn lines.
- **The desk sends them back**: `wire.mjs` decides which shape a save has - a pure module with its own
  node tests, and the CI line now runs every `*.test.mjs` in that folder, so a new file of them is
  picked up by being written.
- **The refusal is now about what is missing**, not about side tracks in general: fewer paths than the
  track has is a page that is out of date (*"Reload the page and try again"*), and **more** is the desk
  trying to add a side track - still the phone's job, and it says so.

## Next

### v0.6.30 - the desk draws side tracks, and GPX carries them

- On the desk: start a side track from a vertex of the traced line, and take one off again. The wire is
  ready for both; what is missing is the page's own state holding more than one path (`geometry.mjs` and
  `edit.js` are single-path today, and their 26 node tests are the shape that would have to change with
  them).
- GPX out already writes one `<trkseg>` per path; reading still flattens every `trkpt` in a file into one
  line, so a multi-segment GPX imported from another tool becomes a line with jumps in it. Read segments
  as paths, and decide what to do with a segment that does not touch the line (probably: keep it as a
  side track only when it joins, otherwise offer to import it as its own asset).

**Loose ends from v0.6.27's verification belong at the front of that slice**: the map's own drawing of a
second path and the GPX `<trkseg>` per path were never checked on a *published* artifact, and
`GpxWriter`'s two-path case has no test of its own (every test passes a single path).

## Decided and dropped

- **Archiving assets** - dropped, not deferred: nothing in the field asks for it, and a track that is
  never coming back is deleted (with its sprays, which the delete sentence counts) .
- One level of side tracks, not a tree. A side track off a side track is not a thing anybody has asked
  for, and one level keeps the storage, the drawing and the arithmetic honest.
- Automatic detection of tracks drawn with the up-and-back trick: **no**. It would be guessing at
  ground the operator knows better, and a false positive would silently shorten a length somebody
  relies on.
