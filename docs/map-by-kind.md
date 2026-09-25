# The map by kind

Release 3 of the five agreed after the kinds shipped (v0.6.40), taken out of the agreed order: per-kind
layers and markers on the map. Written before the work, kept while it was in progress.

**Shipped in v0.6.45.** How a marker is drawn took three more releases to settle: v0.6.46 asks for it
once rather than twice, v0.6.47 makes its white edge a rim (it had come out a solid block on the small
shapes), and v0.6.48 draws it plain, with the edge belonging to the asset whose page is open. The last of
those overturns the *white edge over imagery* below - the one thing this plan got wrong - so read the
body as the plan it was. The decisions are in the README under *The kinds of asset*, and the evidence is
in `build/verify/kind-map.txt`, `build/verify/markers-background.txt` and `build/verify/markers-plain.txt`.

## Why

The map draws the work in four layers - a line layer per line kind, and **one layer for every place** -
and it draws every place as the same picture: a house, in the colour its traffic light says. So:

- a bench seat, a sign, a picnic table and a trough are the same house on the map, and the only place
  the operator can tell them apart is the asset list;
- and the five place kinds cannot be hidden from each other, which is the same complaint the four
  switches were brought in to answer one level up: a block with forty troughs on it buries the two
  buildings that are the thing being looked for.

The list already answers both - eight glyphs, one chip per kind - and this release makes the map
agree with it.

## What ships

**One marker per kind, in the due colour.** The picture a place wears becomes
`kind + traffic-light colour` rather than colour alone: a **building** is a roof over walls, a **sign**
a plate on a post, a **bench seat** two bars over straight legs, a **picnic table** one top on splayed
legs, an **other place** a dot in a ring - each in green, amber, red or grey. That is five pictures
instead of one, per colour.

**One layer per kind.** The single point layer becomes five, each filtered to its own kind, so every
place kind can be shown or hidden on its own. The switches become eight - the same list the chips
offer - and a preference written by an older build that hid *places* hides all five place kinds when
it is read back, because that is what it meant.

**One shape, drawn twice.** The map's markers and the list's glyphs are the same geometry with
different weights: a thin outline beside a name, a fat one with a white edge over imagery. That
geometry moves into one file (`map/KindGlyphs.kt`) which both use, so a bench cannot be a bench in the
list and something else on the map.

**The desk is served the phone's own pictures.** Today the page paints its own house - a second copy
of a shape the phone already draws - and with five shapes that copy becomes five. Instead the phone
renders the marker it would use (the same `DrawScope` code, into a bitmap) and serves it at
`/api/markers/<name>.png?px=<size>`, so the desk asks for the size its own screen needs and draws
exactly what the phone draws. The page's painter goes.

## What does not change

- The lines: three layers, one per line kind, and their dash patterns. They were already per kind.
- The colour rule: the marker's colour is still the traffic light's, never the family's - the family
  colour is for the glyphs beside a name in the list.
- The map's own behaviour: markers still never hide from each other, and are still drawn at the
  device's own pixel density rather than scaled by the style.

## What to prove, and how

- Three or more place kinds on one map, by screenshot: different pictures, in colours that say when
  each is due.
- A switch thrown for one place kind, by pixels: that kind gone and the others still there - the
  thing four layers could not do.
- An old preference: a store that hid `places` hides all five, and one that hid nothing still hides
  nothing.
- The desk: the same markers on its map, fetched from the phone, at the browser's own scale.
- And that a bench and a table are actually different pictures: a test that renders each kind and
  says the ink is not the same.
