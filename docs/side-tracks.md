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

## Next

### v0.6.29 - the desk draws and tidies side tracks

A traced vertex on the desk's map should be able to start a side track, and the version body should
carry the paths so the refusal in v0.6.27 can come out.

### v0.6.30 - GPX multi-segment in and out

Out is done for writing (`<trkseg>` per path); reading still flattens every `trkpt` in the file into
one line, so a multi-segment GPX imported from another tool becomes one line with jumps in it. Read
segments as paths, and decide what to do with a segment that does not touch the line (probably: keep
it as a side track only when it joins, otherwise offer to import it as its own asset).

**One loose end from v0.6.27 belongs here**: `GpxWriter` writes one segment per path, and the unit
tests that cover it all pass a single path, so the two-path case has no test of its own. Writing one
is part of this slice, before anything else in it.

## Decided and dropped

- **Archiving assets** - dropped, not deferred: nothing in the field asks for it, and a track that is
  never coming back is deleted (with its sprays, which the delete sentence counts) .
- One level of side tracks, not a tree. A side track off a side track is not a thing anybody has asked
  for, and one level keeps the storage, the drawing and the arithmetic honest.
- Automatic detection of tracks drawn with the up-and-back trick: **no**. It would be guessing at
  ground the operator knows better, and a false positive would silently shorten a length somebody
  relies on.
