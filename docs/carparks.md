# Carparks

The first kind of asset that is **ground** rather than a line to travel along or a place to stop at.
Written before the work, kept while it was in progress.

The operator's report, 26 Sep 2026: *"We have a number of carparks, where the perimeter is sprayed and
also the interior if needed. Right now I am classing these as fencelines and I am drawing a fenceline
border. I feel that this is not the right way to approach this asset."*

## Why a fenceline is the wrong home

Eight kinds exist and the kind decides the shape: three lines to travel along, five places to stop at.
A carpark is neither, and dressing it as a fenceline costs four things:

- **The word is wrong everywhere it is read.** The list, the chips, the map's switches, the desk's
  *Type* and the handover all say fenceline, so a season's record claims fences were sprayed where it
  was carparks - and filtering to Fencelines to plan a fence round brings the carparks with it.
- **The picture is wrong.** A fenceline is drawn dotted, because a fence is a series of posts. Dotted
  is what a carpark looks like today, and it says *a fence to walk along* about a piece of ground.
- **The interior has nowhere to live.** The app's whole vocabulary for ground is a length, or an area
  guessed as length x swath width. Drawing the interior as further lines would put invented metres
  into the length - and the length is the denominator the coverage percentage, the block's distance
  and the record's own figures are built on. Side tracks are a release of their own precisely because
  metres counted twice are a lie the arithmetic then keeps telling.
- **The number that matters is missing.** A carpark is worth an area, for a rate per hectare and for
  a handover, and the app has never been able to measure one - only to estimate it from a width
  somebody typed. It knows the ground inside a ring the moment the corners are drawn.

## The decision

**A carpark is ground with an edge.** A ninth kind whose shape is a **ring**: the boundary of the
ground, closed on itself. The app then knows both numbers honestly - the metres you travel round it,
and the ground inside it, measured rather than estimated.

**The whole ground is the job**, on the operator's own word: asked whether the middle needs recording
separately, they answered *"I do the whole carpark every time - there is nothing to record
separately."* So there is no tick at the finish, no claim about the middle, and no second due date.
One spray, one next-due date, one job. The tick was the other answer to that question and it is the
obvious thing to add if a season of use says otherwise; until then it is a question the app would be
asking to fill a field nothing reads.

Two alternatives were considered and rejected:

- **Two assets for one carpark** (an edges line and the middle as something else). Two rows to keep in
  step for one thing, and the middle has no shape of its own to draw - a second ring over the same
  ground reads as a mistake on the map.
- **Keeping the fenceline and adding a note.** The word, the picture and the missing number are the
  complaint, and a note fixes none of them.

## What ships

**The ninth kind.** `AssetKind.CARPARK`, *Carpark* in the picker, the chips, the switch and the
handover - the app's usual rule, one vocabulary everywhere. It sits between the lines and the places
in the picker's order, so the order keeps saying what it has always said: the lines first, then the
ground, then the places.

Its glyph beside a name is a **filled shape** - a piece of ground rather than a thing standing on it -
and it takes a **fourth colour family**, because it is neither a line you travel along nor a place you
stop at, and the colour's job is to say which of the three it is. The colour is picked against the two
card backgrounds the existing test pins, none of them the traffic light's green, amber or red. If no
candidate survives both backgrounds the ground shares the fenceline's teal and the filled glyph
carries the difference; that fallback is written here because the test is what decides it.

**A third shape: the ring.** `AssetShape.AREA` beside `LINE` and `POINT`, decided by the kind and
never asked as a second question. A ring is a line that comes back to where it started, and it is
**stored closed**: the last vertex is the first corner again, written by the app where the shape is
settled. The alternative - leaving the closing side to be implied by the kind - would make every
place that measures ask what shape it is holding: the cached length, the coverage walk, the hit test,
the GPX writer, the desk's own fingerprint. The app's own precedent is the accuracy ring
(`PositionGeoJson`): *"a GeoJSON polygon that does not close is not a polygon."*

**Drawing one.** On the phone: tap the corners, then *Join it up* - a button that appears once there
are three of them - with the bar saying what to do and the ground inside shown as it grows. On the
desk: the same with clicks, closing the same way. Both carry one path, so the wire, the version
fingerprint and the backup keep the shapes they have.

**Being one, on the card.** *Round it 160 m · 0.35 ha of ground* - the area with no "about" in front
of it, because it is measured from the corners and not estimated from a width. The form stops asking
a carpark for a swath width and a pass count, and says why in one line: those two are line ideas, and
a carpark's area and one run round it are not.

**The map.** A shape, not a fence: the edge drawn solid in the kind's own line style, and the ground
inside in a light fill of the same colour, so a carpark reads as ground from a distance. A part-walked
edge keeps the per-stretch colours it has on any line - that the north side was missed is worth
seeing - and the fill carries the carpark's own traffic light. It gets a switch of its own, which
hides both of its layers; it asks for no marker picture, because a carpark is not a place; and a tap
**inside** it opens it. That last one is the first time the app answers *what did I tap* with ground
rather than a line, so the rule is written down: a line under the fingertip still wins, the ground
answers a tap no line claims, and two carparks under one tap resolve the way two lines always have -
the lowest id, so the answer never depends on the order the map was built.

**The numbers, and where they live.** The perimeter includes the closing side, so an edge walked all
the way round reads as 100% rather than 96%. The ground inside is measured from the corners on a local
flat - a carpark is metres across, so the difference from the sphere is under a square metre. Both
join the cached length on the asset's own row, because a list must not join point rows to add a block
up, and both are written wherever the geometry is written. The backup carries the area beside the
length it already carries, as a key an older file simply does not have.

**The record.** Nothing new is asked of the recorder: a run round the edge is a pass on a path, with
the coverage reading, the part-done colouring and the two-pass rules it already has - and a carpark is
one pass, so the "did you do both sides?" question cannot arise on one. The spray's area is the
carpark's own ground: a measured number in the handover's own column, where a line's has always been
an estimate. A block holding a carpark still says *about* its area, because a total mixing measured
and estimated ground is only as good as its weakest part.

**Nothing already recorded is rewritten.**

- A carpark kept as a fenceline stays one until somebody says otherwise. Changing its kind is the fix,
  and the form says what that will do - a line of three or more corners closes into a carpark, and a
  line of two is refused in the app's own words rather than closed into something that encloses
  nothing.
- A phone on an older build reads a carpark as a track - the kind it has never heard of - with the
  shape beside it still a line and the same points: `AssetKind.fromStorage`'s existing rule, unchanged.
- Every other kind, layer, preference and record is exactly as it was. An install that never makes a
  carpark cannot tell this shipped.

**GPX.** GPX carries tracks, not areas, so a carpark goes out as its corners - a closed track, which
is what any tool reads it as - and comes back as a line. One edit turns it back into a carpark, and
the app does not guess at the shape on the way in.

## Not in this change

- **The middle, recorded separately.** The operator does not need it - see *The decision* above. It is
  the first thing to revisit if a season says the middle really is skipped and the record should say
  so.
- **The middle, checked from the pass.** Reading a pass *over* ground rather than *along* a line is a
  feature of its own - the app would have to cover a surface, not a path - and it should wait until
  the simple version has been used.
- **Other kinds of ground.** A yard, a pad, a lawn, gravel under a tank: one kind at a time, in the
  operator's own words, and only when asked for.
- **Holes, islands and a general drawing tool.** One ring per carpark, one level - the same reasoning
  that keeps a side track a side track.

## What to prove, and how

- **The arithmetic, by test**: a square of known size reads its own perimeter and its own ground; the
  closing side is in both; three corners are the least that will do, and two are refused with the
  app's own sentence.
- **The kind's answers, by test**: the word, the colour family, the glyph, the switch's two layers,
  the marker picture it never asks for, and that an old preference that hid *places* hides no carpark.
- **The tap, by test**: a tap inside opens it, a tap outside does not, and a line under the fingertip
  still wins.
- **The pixels, by screenshot**: the shape on the map beside a fenceline and a track, the chip, the
  switch, the card's two numbers, a half-walked edge coloured in halves, and the drawing screen
  closing a ring.
- **The desk, by dump**: drawing a carpark from the browser, seeing it drawn as ground, the *Type*
  list, and the handover's area column.
- **The published build**, at the release, the way every release is checked.

## How it lands

1. **The kind and the ring** - the shape, the arithmetic, drawing and closing one, the card, the chip,
   the switch, the map's shape and the tap inside it, and the record's numbers. This is the release
   that answers the report. **Shipped in v0.6.53**; what was proved, and what was not, is in
   `build/verify/carpark.txt`.
2. **The desk** - drawing and editing a carpark from a browser, and the handover's area column.
3. **Only if asked for** - the middle, either as a thing recorded on its own or as ground the app
   checks for itself.

