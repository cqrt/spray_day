# Place names over the imagery

Aerial imagery is a photograph. It says what the ground looks like and nothing about what any of it is
called, which is a real question at a kitchen table: *that paddock is beside which town?* The **drawn
map** (OpenStreetMap) answers it, but choosing it means giving up the photograph - and the photograph
is what this app is for, because what the imagery shows is what a spray decision is made from.

So the names come as a **layer of their own**, over the imagery.

## Where they come from

LINZ publishes the same data the imagery comes from as **vector tiles**, and one layer of them is a
point per named place. It is the same service, the same key and the same licence as the imagery
(CC BY 4.0), so nothing new is being trusted and the credit the map already carries covers it.

Two things about that service are worth writing down, because both were checked against it rather than
read off a page (`build/verify/place-names.txt`):

- **The tileset is `topographic-v2`.** The obvious-looking `topographic` answers **HTTP 200** and has
  **no `place_labels` layer at all** - it is an older schema. A tileset named wrongly here turns the
  feature off without turning anything red, which is why `PlaceLabelsTest` pins the name.
- **The layer is `place_labels` and the name is in `label`** - not the `name` a vanilla Shortbread
  tile would carry. `place` says what kind of place a point is (city, town, village, locality, suburb),
  and `natural` says peak, bay, cape or peninsula.

The tiles go to **zoom 15**, and past it the map overzooms: it keeps drawing the deepest tile it has,
scaled up, which for a point with a name on it is exactly right - the name stays where the place is
and only its lettering grows.

## The bands

Names are drawn in four bands, because what changes between a wide view and a close one is *when* a
name should appear at all, and a MapLibre layer's zoom range is a layer property rather than a filter's:

| Band | What it draws | Files it under | Appears | Size |
| --- | --- | --- | --- | --- |
| `linz-place-towns` | `city`, `town` | `place` | zoom 6 | 11 → 14 |
| `linz-place-lakes` | `lake` | `place` | zoom 7 | 11 → 14 |
| `linz-place-localities` | `village`, `locality`, `suburb` | `place` | zoom 10 | 10 → 13 |
| `linz-place-peaks` | `peak` | `natural` | zoom 11 | 10 → 12 |

A wide view with every locality on it is a muddle; a town name is what the operator zoomed out to
find. The same argument the app's own track names are held back with, and the same shape LINZ's own
topographic style uses.

**Peaks wait longest of the four**, further than a named summit might seem to deserve: LINZ publishes
them from low zooms, a range has dozens, and a wide view with every summit named is a screen of names
with the ground lost underneath. They are also the band no spray decision is made from - they are for
reading the country rather than the paddock.

LINZ files these under two properties, and a band says which it means: everything a person lives in or
near is `place` (city, town, village, locality, suburb, **lake**, island, archipelago) and the
landscape is `natural` (peak, bay, cape, peninsula, and the water kinds below).

**Not drawn**, though they are there in the same layer: bays, capes, peninsulas, lagoons, the open sea,
islands and archipelagoes. Each is a band away, and each is more clutter over a paddock; see
`build/verify/place-name-kinds.txt` for what else the tiles carry.

## How they are drawn

- **Over the imagery, under the work.** The layers are added straight after the raster source, so an
  operator's lines, track names and markers are never covered by a town name. The work is what the map
  is for; a place name is the furniture around it.
- **White letters with a dark outline**, as the app writes its own names - white because it is the one
  colour the traffic light never uses, and because over a photograph (green paddocks, grey roads, brown
  hills) white with a dark edge is what is readable. The outline is thicker than a track name's: what
  is behind it is a photograph rather than a flat map.
- **A name that will not fit is dropped**, rather than drawn over the name it would touch. This is the
  opposite of how the app treats its own track names - which are never allowed to vanish - and
  deliberately so: a track's name is something the operator put there, and a town's is not.
- **The letters travel in the app.** The names are text the map writes, from the same Noto Sans files
  the track names use (`GlyphFonts`), so a name on the desk is written from the letters that ship in
  the APK rather than from a font the laptop happens to have - and Māori names draw with their macrons.

Every number above lives in `domain/tiles/PlaceLabels.kt`, and both maps read it from there: a town
written one size on the phone and another on the desk is the drift the file exists to stop.

## The switch

The switch is **off by default**, and it lives in the map's own **"What the map draws"** sheet - the
layers button on the map - where its row reads *"Towns, lakes and peaks from LINZ, written over the
imagery."* It is there because that is where this app puts every question about what is on the map, and
because the question "why is that town name there" is asked while looking at the map.

It is the one switch in that sheet that starts *off* (everything else there is the work, which is why
the app was opened) and the one that fetches something new. Turned on, `place_names` is written to
preferences and the map reloads its style: unlike a layer being hidden, the names are a **source** the
style either has or has not got.

**The desk follows the phone.** The page's style is built by the phone
(`WebStyleJson`), so the names appear on the laptop exactly as they are switched on the phone, fetched
from the phone - the page never talks to LINZ, and the key never leaves the phone.

## Offline

The names go through the app's own tile server, the same as the imagery: fetched from LINZ on the way
past, kept in the store, and served to both maps from there. So **anywhere that has been looked at
keeps its names with no reception**, and nowhere that has not, has none - the same rule the imagery
follows, and the reason the two are worth having together.

They are kept in the **cache** directory rather than the app's own files: what is here is what the map
picked up on its way past, not something downloaded on purpose, so it is not carried in a backup and
may be reclaimed under storage pressure. Folding the names into the **Offline areas** download - so a
block downloaded for a trip with no reception keeps its names too - is the obvious next step, and it
is not done yet.

## Files

| File | What it is |
| --- | --- |
| `domain/tiles/PlaceLabels.kt` | **The one table**: the tileset (`topographic-v2`), the layer (`place_labels`), the property the name is in (`label`), the zooms, the bands with their kinds and sizes, the colours, the outline, the credit, and the URLs (a tile, and the template a style is given). Both maps read every number from here. |
| `map/BasemapStyles.kt` | The phone's style document: the vector source and one symbol layer per band, when the operator has asked for them *and* there is a server to fetch them from. |
| `map/WebStyleJson.kt` | The desk's style, built from the same table: the same source, the same bands, the same numbers. |
| `offline/TileServerHolder.kt` | The source in the app's tile server: the LINZ fetcher with the vector URL, the store in the cache directory, the content type. No key, no names. |
| `data/SettingsRepository.kt` | `place_names`, **off** when unset, with the reasoning on the preference. |
| `viewmodel/MapViewModel.kt` | The switch, written straight through like the layer switches. |
| `ui/screens/MapScreen.kt` | The row in "What the map draws", and the second credit in the attribution strip. |
| `map/BasemapView.kt` | `placeNames`, which reloads the style rather than hiding a layer - the names are a source. |
| `web/WebEditorDocuments.kt` | Reads the switch when the desk asks for its style, so a page reload is enough for a change to take effect. |
| `app/src/test/.../PlaceLabelsTest.kt` | The tileset, the URLs and the bands, held by a test. |

## Notes and limits

- **The dataset is LINZ's**, under CC BY 4.0, the same as the imagery: the credit strip adds
  `© Topographic data` when the names are on, because the imagery's own credit already names the
  publisher and the licence.
- **Names are only as current as the tiles**, which are LINZ's own and updated by them.
- **A town name is furniture, not work.** Nothing here is stored against an asset, nothing is imported,
  and switching the names off changes nothing but the picture.
- **The phone's own map draws them too**, from the same style document: the feature is not a desk
  feature that the phone happens to serve - it is one map drawn on two screens.
