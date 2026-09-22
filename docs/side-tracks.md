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

## Shipped: v0.6.33 - a side track can be worked on like the line

Reported from use: *"trying to delete or move points on a side track but that feature does not exist, can
delete and move points on a main track but not on side tracks."* Exactly right, and for a simple reason: the
handles, the delete key and the drags belong to **the path in hand**, and the only thing that could put a
side track in hand was starting a new one. A side track that had been saved came back as a path with no
gesture pointing at it.

- **A click on another path takes hold of it** (`otherPathAt` + `hold`), and changes nothing about the
  drawing — so a side track becomes the path in hand, with its own handles, and Del and the drags apply to it.
  Taking hold is deliberately *not* a step of the history: Ctrl+Z still takes back the last change.
- **The path in hand is what a click on it edits**: the path in hand still means "put a point in here", which
  is why the click that *selects* is a click on a **different** path, and why a click at a junction belongs to
  the track it is on rather than to the spur.
- **The hand says which is which**: a *grab* over a handle (the click drags a point), a *pointer* over another
  path (the click takes hold of it), a crosshair on bare ground (the drawing goes there).
- **The bar says which path is in hand**, and while the line is in hand it now says *"click a side track to
  work on it"* — because that is the gesture the report could not find.
- **A side track Del-ed down to one point comes off whole.** One point is not a strip and the phone refuses a
  path of one, so Del on its last point ends the side track and hands the line back, the same rule
  `backToLine` applies to a strip that never got a second point.
- **Dragging the point a side track hangs off moves the line**, because that point *is* a line vertex: the
  junction rule says so, and the line's own move carries every side track hanging off it. Before this, the
  only way to move a junction was to let go of the side track first.
- `drawingSideTrack` became **`sideTrackInHand`**, which is what it had always meant: a side track that has
  just been started is in hand *and* unfinished, and every side-track affordance hangs off the second half.

Proven by driving the desk in a browser (`db/deskdrive.mjs`), in the bar's own words:

    5 points · 3.89 km · 1 side track    the side track is what you are working on   <- taken hold of by clicking it
    3 points · 3.34 km                   the side track Del-ed down to a point, so it came off whole
    5 points · 3.89 km · 1 side track    after Ctrl+Z, back exactly
    5 points · 4.35 km · 1 side track    after dragging one of its handles

and the body it PUT carries the junction moved onto the line's own vertex, to the bit:

    paths[0]  [-41.5,173.9] [-41.499461…,173.92] [-41.504259…,173.945624…]      the line, with the picked point
    paths[1]  [-41.504259…,173.945624…] [-41.505,173.94]                        the side track, junction moved with it
    paths[2]  [-41.499461…,173.92] [-41.505858…,173.930392…]                    a new side track, off the picked point

`paths[1][0]` being `paths[0][2]` exactly is the junction rule surviving a drag. Five new node tests (56 in
all), including the one that pins taking hold as *not* a history step.

## Shipped: v0.6.32 - the side track leaves the track where you click it

v0.6.31 could draw a side track, but only where the track ended: the operator clicked the fence they wanted
to branch off, pressed *Side track*, and the spur was hung off the last point of the line - miles away, with
a strip dragged across the paddock to prove it. Reported in those words, and this is the fix.

- **Clicking the track picks the junction.** A click on the line puts a point in it (which it always did) and
  that point becomes where a side track will leave - said in the bar (*"a side track will leave the track at
  the point you clicked"*) and drawn as a filled dot, because on a map full of fences an invisible choice is
  no choice.
- **`startSideTrack(state, junctionIndex)`** takes the line vertex to hang off, or the end when there is
  none: drawing a track from scratch has nothing to choose, and a picked point can stop existing between
  being picked and being used (Undo, a delete) - in which case the end is the honest answer rather than a
  path that starts nowhere.
- **The junction is a vertex, not a place**: the spur's first vertex is the line's own, to the bit, and
  dragging that line vertex takes the spur with it - so the two paths cannot drift apart while one of them
  is tidied.
- **The line is not split.** A spur off the middle leaves the rest of the line exactly as it was, and the
  line carries on from its own end afterwards.
- The choice is consumed by the spur that uses it, so after *Back to the track* the next side track leaves
  the end again until the operator clicks the track once more.

Proven in the browser driver (`db/deskdrive.mjs`), and the proof is in the bytes it PUT:

    paths[0]   the line, with the picked point in it:  -41.5,173.9  ·  -41.499995…,173.92  ·  -41.5,173.94
    paths[1]   the existing spur, untouched
    paths[2]   the new spur, starting at -41.499995…,173.92  - the point that was clicked

The last of those is the whole claim: the spur left the track at the point under the mouse (lng 173.92, the
middle of the line), **not** at the track's end (lng 173.94). The bar read *5 points · 3.89 km · 1 side
track* with the point picked and *6 points · 3.89 km · 2 side tracks* once the side track had started from
it. Five new node tests (51 in all): the picked vertex, the line left whole, a drag carrying the spur, a
stale index falling back to the end, and `junctionFeature`'s `[lng, lat]`.

**The phone's own drawing screen still leaves the track where it ends.** It is the same limitation the desk
had, and now the odd one out; the phone's flow is worth the same treatment, and it is on the next list rather
than in this change.

## Shipped: v0.6.31 - the desk draws side tracks

The desk could carry a track's side tracks from v0.6.29 but not make one, so a spur was something only a
phone could put on a track. It draws them now, with the phone's own two moves in the phone's own words,
and the phone takes what it draws.

- **The page's state is the paths** (`geometry.mjs`): path 0 the line and the rest its side tracks, which
  is what the phone stores, what a backup carries and what a write has on the wire — so nothing is
  re-interpreted at either end of a save. `active` is which path the clicks, drags and traces go to, and a
  history snapshot carries it, so Ctrl+Z after starting a side track hands the line back.
- **The gesture is the phone's**: `startSideTrack` puts a new path on the line's own last vertex — the same
  two numbers, not a copy, which is what the junction rule asks for — `backToLine` drops one that never got
  a second point, and `dropSideTrack` takes off the one being drawn. `B` and `L` on a keyboard do the same
  as the two buttons, which is the whole of the page's own furniture for it.
- **The junction follows the map**: dragging a line vertex carries any side track that hangs off it, and
  taking that vertex off the line takes the strip with it (one Ctrl+Z brings both back). A strip that hangs
  off nothing is a drawing the phone refuses, so the page never makes one by accident.
- **The bar says the number and the metres**: *4 points · 3.89 km · 1 side track* while a track with a spur
  is open, and *6 points · 4.28 km · 2 side tracks* after a second one is drawn — the new spur counted
  **once**, which is the arithmetic the phone does.
- **The wire shape follows the drawing now**, rather than merging a drawn line into a held record: one path
  is `points` (what every page before this sent) and more is `paths`, line first.
- **The phone's count refusal is gone; the staleness one is not.** A body carrying `paths` is taken
  whatever number it holds — the rules (`AssetPathEdits`) judge the shape of what arrived — while a body of
  a single `points` for a track that has side tracks is still refused, because that page never read the
  record's `paths` and a write would drop every spur on the track.

Proven in a browser driving the desk (headless Edge over the DevTools protocol, `build/verify/db/deskdrive.mjs`
against the scratch page server): the buttons appear in the right two states, the bar's numbers above are
what it said, and the save PUT three paths — `[line, spur, new spur]`, line first, no `points` field —
which is the body the phone's own rules take. **Not proven**: that the map canvas paints the second path.
The headless browser here renders the style's background and nothing else, so the screenshots are no
evidence of a drawing; `pathsFeature` (one feature per path) is unit-tested and the pixel claim is left to
a browser on the machine the desk is being used from.

## Shipped: v0.6.30 - GPX carries the side tracks

- **Out**: one `<trkseg>` per path, so a spur comes back as a spur. A track with no side tracks is written
  exactly as it was before this slice existed.
- **In**: a file with more than one segment is read as paths when **every** segment after the first begins
  at the vertex the one before it ended at, which is the junction rule the app draws and edits by.
  Otherwise it is read the old way - one line, every point - and the answer
  says so, because a track with a jump in it is worth knowing about rather than worth refusing.
- **The sentence says which**: `Imported "x" with 5 points: the line and 1 side track.`, or
  `... as one line: the file's own track segments do not meet, so they were joined up.`

## Next

### v0.6.33 - a side track on the phone leaves the track where you touch it

The desk's own drawing screen picks its junction by a click on the track (v0.6.32); the phone's expects the
operator to draw *to* the junction and hangs the spur off the end of the line. The phone's rules are the
same ones, so this is the screen catching up with the desk rather than a new rule - and after it the two
behave identically on the same ground.

### v0.6.34 - GPX drag-and-drop onto the desk, and more than one asset at a time

Phase 3 of `web-editor.md` has one item left before the desk is done: dropping a GPX file onto the page.
The desk can now draw every part of a track, so what a dropped file has to become is a drawing the page
already knows how to hold.

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
- **The refusal was about what is missing**, not about side tracks in general: fewer paths than the track
  has was a page that is out of date (*"Reload the page and try again"*), and **more** was the desk
  trying to add a side track — which v0.6.31 then taught it to do, so only the first half is left.

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
