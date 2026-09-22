# Spray Day

GPS track planning and spray records for set spray tracks — built for the way
rural tracks actually get sprayed: the same lines, roughly three times a year.

The point of the app is the map: open it and see at a glance **which assets are
due**, coloured green (not due), yellow (due soon) or red (overdue / never
sprayed).

## What it does

- **Basemap of your choice** — LINZ aerial imagery (New Zealand) by default, or
  OpenStreetMap for roads, tracks, gates and names, which needs no key and keeps working
  the day a LINZ key expires. Whichever is chosen, the credit its licence requires is
  shown permanently on every map, and **offline areas** download aerial imagery so the
  map still works where there is no reception. **Where you are** is a dot on **every** map — the
  map, the recorder, the drawing screen, an asset's own map, the offline picker —
  ringed by the accuracy the fix was actually good to: a fix under trees is drawn as
  the uncertainty it is, and a fix with no accuracy is drawn as a dot with no ring,
  because unknown is not the same as small. A **locate** button puts the camera back
  on you, and is where the map asks for location permission — on a tap, not on the
  way in. The dot follows you while a map is open and stops when you leave it, so it
  costs nothing to keep on.
- **Settings** with the LINZ key field, so an expired key is fixed on the phone
  rather than by shipping a new build. **Check** asks LINZ about the key in force
  and reports LINZ's own answer — accepted, expired, or rate limited.
- **Assets** — tracks, roads and pieces of infrastructure, in one list: import GPX,
  draw them by tapping the map, or record them by driving the line. The drawing screen
  asks what you are making as you make it, so a fenceline is drawn as an infrastructure
  line and a trough is placed with a single tap. Each row carries a coloured icon of
  what the asset is, next to the colour that says when it is due.
- **Drawing from a computer** — a laptop on the same Wi-Fi can be served the same work the phone
  holds, and change it. A track's details can be changed from its card; its line can be tidied by
  hand, by dragging the handles, clicking the line to put a vertex in the middle of it, Delete to
  take one off and Ctrl+Z to take a step back — none of which reaches the phone until you press
  Enter; a whole new track can be drawn from scratch; and a mis-drawn one that has nothing recorded
  against it can be deleted, while one with sprays on it says so in numbers and sends you to the
  phone. A line is laid two ways: **click** where a corner is, or **hold the button down and follow
  the fence on the imagery** — the two mix, and a traced fence is one Ctrl+Z rather than twenty.
  Everything is judged by the phone's own rules and written to the phone's own database, so
  the laptop and the phone cannot disagree about what is on the farm. The address the card shows
  carries a secret code that the phone checks, and **"Only this address can open it"** turns that
  off for a network you own — with it off the address is a plain one and anyone on the Wi-Fi can
  open the editor and change your tracks, so the card says that in as many words.
- **Per-asset settings**: each asset carries its own spray interval (120 days is
  only the default), its **kind** (track, road or infrastructure), whether it is a
  line or a single spot, how it is sprayed (**boom** or **knapsack**, which offers the
  usual width for that method), the swath width for a treated-area estimate, how many
  passes finish the job (**one**, or **two** for a line walked up one side and back
  down the other — see [A line that is sprayed twice](#a-line-that-is-sprayed-twice)),
  a **block or group** to work it with (assets sharing one fold into a single tile in the
  list), and notes. The interval is what the traffic light uses,
  so a block sprayed on a shorter cycle turns yellow on its own schedule.
- **Spray records**: pick an asset, enter the products and the **mL of each**,
  save. The asset turns green and its history starts. Amounts are remembered per
  asset, so the next pass is a confirmation rather than a retype.
- **A spray record you can correct**: the bin beside an entry in an asset's history takes that
  spray off — one logged against the wrong asset, or with the amounts mistyped — and the asset's
  colour is worked out again, so removing the only one puts the line back to red.
  **Clear spray history**, in the asset's own actions, does the same to the whole record, for
  starting an asset again. The asset, its line and its recordings stay where they are: what goes
  is the record of what was sprayed, and the colour the line was earning from it.
- **Due reminders**: a notification when an asset comes due, so a spray window is not
  discovered a fortnight late. Nothing repeats daily, and a line drawn this morning
  is not nagged about.
- **Handover record**: the season as a CSV — one row per product per spray, with the
  asset, its group and spray method, the amount, water, distance, and the recording
  that proves it. Dates are ISO so a spreadsheet sorts them, and the file carries a
  byte-order mark so Excel opens accented names correctly.
- **The map is the home screen**: every asset drawn in its traffic-light colour, and
  each kind drawn differently — solid for tracks, dashed for roads, dotted for
  infrastructure, and a **house for anything that is a spot** rather than a path, in
  the same traffic-light colour and the same house the asset's row carries. A
  **part-sprayed track is drawn in parts**: what the pass covered in the colour it
  earned, and what is still waiting for a tank in red, so "half this line is left" is
  visible without opening anything. A tap
  on an asset opens it, and the tap radius follows the zoom, so it is tappable zoomed
  out over the farm as well as at spray height. The map does not rotate: north is up,
  which is one less thing to get wrong with gloves on.
- **Layers you can switch off**: the layers button on the map lists what it draws — tracks,
  roads, fencelines and stopbanks, and places — and hides any of them, so the map can be
  cleared down to what is being read. The choice is remembered between visits, and it is the
  home map's own: a spot being drawn still appears while it is being drawn, and an asset's
  page still draws that asset.
- **Backup and restore**: one JSON file holding the whole season — assets and their
  lines, every spray with its amounts, the groups and the product catalogue, the
  amounts each asset remembers, and every GPS recording. A restore replaces, and says
  what is on both sides of that before it does anything. Files written before groups
  existed still restore: their "block or area" becomes a group of that name.
- **Off-site copy**: the same backup, written by the app itself — to a file you chose, or
  to a private GitHub repository where every backup is a commit, so the copy from before a
  mistake is still there. An empty database is never written over it, so a phone that has
  been wiped cannot destroy the last record of the season.
- **Updates from inside the app**: nothing else will ever mention a new version, because
  this is not installed from a store — so it asks GitHub once a day, says so with a
  notification, and installs the release from Settings, handed to Android's own installer.
- **GPS recording** via a `location`-type foreground service, with every fix
  written to the database as it arrives, and a pause written down as a pause — so a
  stopped pass leaves ground that reads as unsprayed, while a gap where the fixes
  only went missing is ground the pass drove.
- **Spraying while recording**: pick the asset, type the amounts as they go in,
  and watch a live **coverage percentage** of the planned line — with the line
  itself turning green where this pass has sprayed it and staying red where it
  has not, and the track's own length beside the number, so a track shorter than
  the job it is being driven for is visible before it is driven. The map **follows
  you** while a pass is being driven, so the line you are on stays under the screen
  without a hand on it; dragging the map hands it back to you, and the follow button
  at the top right says whether it is following and puts the camera back on you when
  tapped — while a pass is being driven. Pause, and the button goes with the following:
  the map is yours to read while you are stopped, and carrying on turns following back
  on by itself. Finishing saves the recording and the spray together, linked by session id,
  so the traffic light updates, the spray history carries the distance actually
  driven, and a line that was only part done comes back on the map in two colours.
  **The pass is the spray**: amounts are there for the record's sake, and a pass over
  the chosen asset records the spray whether or not any were typed — a line driven
  with the sprayer on is a line that has been sprayed. The one exception is a line
  that takes two passes, where the spray waits for the pass that finishes the job.
  **The pass stays on the screen after it is saved** — the line in its two colours,
  the distance, the time, the points, the coverage and the track's length — so the
  screen a second after Save is the record of what was just done rather than a blank.
  It is kept for as long as you are on the recorder and no longer: move to another tab,
  or put the phone down, and the next visit opens **ready to record** instead of still
  carrying the last job's numbers and the sentence about saving it.
- **GPX export** of any asset, shareable to QGIS/Google Earth/forestry tools.
- **Recordings browser**: every GPS recording kept as evidence, showing the line
  that was driven, the plan it was for, and how much of the planned line it
  covered. Deleting a plan never deletes the recording.
- **Product catalogue you can keep tidy**: rename a product without breaking its
  history, or archive one so it stops being offered without losing what was
  sprayed with it.

## Basemaps

Two maps can be drawn under the work, chosen in **Settings** and used everywhere a map is: the
map itself, the recorder, the drawing screen, an asset's map and a pass's own map.

| Basemap | What it is | Key | Offline |
| --- | --- | --- | --- |
| **Aerial imagery** (LINZ) | A photograph of the ground — what a spray decision is made from | LINZ Basemaps key | downloadable |
| **OpenStreetMap** | A drawn map: roads, tracks, gates and names | none | browsed as you look at it |

OpenStreetMap is in the app for one reason above all: the day a LINZ key expires, the map is
still a map. It shows nothing about what is growing, so it is not a replacement for imagery —
it is a floor under it, and it is also the map to read when the question is which gate a
fenceline starts at.

### OpenStreetMap's terms, and what they mean here

Their [tile usage policy](https://operations.osmfoundation.org/policies/tiles/) is a licence
term rather than a preference, and three parts of it shaped the code:

- **No bulk downloading, and no offline packs.** **Download area** is therefore aerial imagery
  only, the offline screens say so, and `Basemap.prefetchable` is `false` for OpenStreetMap — the
  rule sits in the data, where the next feature has to read it, rather than in a comment. Tiles
  the operator has actually looked at are still cached as they are looked at, which is what the
  same policy requires.
- **A `User-Agent` naming the application.** The map never talks to `tile.openstreetmap.org`:
  their tiles are fetched by the app's own tile server, and `OsmTileFetcher` sends
  `SprayDay/<version> (+https://github.com/cqrt/spray_day)`. A library's default user agent is
  exactly what their policy names as not enough.
- **Attribution that is visible.** `© OpenStreetMap contributors` links to
  [openstreetmap.org/copyright](https://www.openstreetmap.org/copyright), in the same
  always-visible strip the imagery credit uses, and Settings carries their
  [Report a map issue](https://www.openstreetmap.org/fixthemap) link — a gate that is missing
  from the map is the sort of thing an operator would know about.

### Where the tiles live

Each basemap is one `TileSource` in the same loopback server, so the map has one tile path
whatever it is drawing: `http://127.0.0.1:<port>/tiles/<source>/{z}/{x}/{y}<suffix>`. The source
in the path is what keeps two licences, two suffixes and two caches apart:

- `filesDir/tiles/{z}/{x}/{y}.webp` — **aerial imagery**, at exactly the path it has always had,
  so a season of downloaded imagery is not re-fetched by this feature existing. It is what
  offline areas are downloaded into, and it is deliberately kept.
- `cacheDir/tiles-cache-osm/{z}/{x}/{y}.png` — **the drawn map**: a cache of what has been looked
  at, which Android may reclaim under storage pressure and which nothing but the map itself ever
  asks for.

Everything that differs between basemaps — the tile URL, the suffix on disk, the content type,
the zoom the source publishes to, whether a key is needed, whether it may be fetched ahead of
time, and the credit its licence requires — is one entry in
[`domain/tiles/Basemap.kt`](app/src/main/java/nz/mckenzie/sprayday/domain/tiles/Basemap.kt).
Adding a basemap is that entry, a store decision in `TileServerHolder`, and a fetcher if the
source is not one of the two already there.

## How offline imagery works

The map never talks to a map service directly — LINZ or OpenStreetMap. Tiles are served by a
small HTTP server inside the app, bound to `127.0.0.1`, which:

- serves a tile from that source's on-disk store when it is held, so downloaded areas —
  and imagery you have simply browsed — work with no reception;
- otherwise fetches it from LINZ (using the key in Settings) and stores it on the way
  through, so ordinary use of the map builds up offline coverage;
- answers 404 for a tile that is neither held nor available, so the map shows
  its background colour rather than an error.

**Download area** fetches exactly the tiles the zoom pyramid needs: the tile
count shown before you commit is the count downloaded, verified on a device
(304 planned, 304 stored, ~5 MB). It resumes rather than restarts, and a killed
download picks up where it stopped.

The area offered is centred on the operator, never on a guess: the device's
current position if the app may know it, otherwise the middle of the operator's
own assets, and if neither is known the screen says so rather than quietly
caching somewhere they have never been. The screen names the centre in degrees
and draws the box on a map, because "Spray area" with no location is not an
answer to "what am I downloading?".

**Choose an area on the map** does the same by hand, for a block that is not
where the phone happens to be: tap two opposite corners, then set the shallowest
and deepest zoom levels to cache - the tile count and download size move as the
sliders do, so the cost of another level of detail is visible before committing -
and give the area a name to find it by later. Exactly those tiles are fetched,
and the area's progress is in the list on the way back.

Tiles are plain `{z}/{x}/{y}.webp` files under `filesDir/tiles`, at the path they have always
had — the source is in the URL rather than in imagery's own path, see **[Basemaps](#basemaps)** —
rather than MapLibre's offline database, which keeps them inspectable, resumable tile by tile, and
servable straight back to the map. That matters because MapLibre's own downloader, pointed at
LINZ's hosted style, pulled ~10x the needed tiles (3,175 resources for a ~304-tile area) by walking
style sources the imagery layers never use.

One subtlety worth knowing: MapLibre gates HTTP requests on device connectivity,
so with no reception it requests nothing at all — including from our local
server, which is precisely where the offline imagery is. The app therefore tells
MapLibre it is connected (`MapLibre.setConnected(true)`); the server, not the
radio, decides what is available.

### What a dead key actually looks like

Worth knowing, because it is confusing in the field and it shaped the Settings
screen. LINZ serves tiles through a CDN that keys its cache on the path alone, so
a tile anyone has already fetched keeps coming back `HTTP 200` whatever key is
sent with it. Measured directly: a popular tile answered with **byte-identical**
imagery for a bogus key, for the real key, and for no key at all, at zoom 12 and
zoom 16. Cold tiles are enforced — a zoom-19 tile over rural Southland returns
`400` for a bogus key and `200` for the real one.

So an expired key shows up as imagery that still works where you have already
been and is blank somewhere new, rather than as an obviously broken map. That is
also why **Check** probes LINZ's hosted *style* document instead of a tile: the
style is enforced per key even on a path everyone requests (`400` bogus, `200`
real), whereas a tile probe calls a dead key good.

## Reminders

The same check runs twice a day in the background (WorkManager, so it survives a
reboot and waits for the phone to come out of Doze) and on demand from **Check now**
in Settings — which is also how you find out what the background job would do.

The rules live in one place, `DueReminderPlanner`, because they are the whole feature:

- an asset is mentioned when it first becomes due, again if it goes from *due soon* to
  *overdue* (that escalation is the news), and after that at most once a week while it
  stays due;
- a never-sprayed asset is left alone until it is as old as the lead time: a line drawn
  this morning has not been missed, it has just been drawn;
- nothing is recorded as "already said" unless the notification actually appeared, so
  turning notifications on later does not find a week of reminders already spent.

Android 13 and later need `POST_NOTIFICATIONS`. Settings asks for it and says plainly
when it is missing, because a reminder system that is silently not permitted is worse
than no reminder system at all.

## Blocks

A **block** is the named collection of assets worked together: the estuary road, the lagoon and
the lower track, sprayed and reported on as one thing. The code calls them groups — `groups`,
`groupId`, `GroupEntity` — and the field on an asset says "Block or group" so that the word in
the table and the word on the screen are recognisably the same thing.

The asset list folds by block. A block's assets are drawn as one tile until it is opened, and
the tile answers what would otherwise need the rows:

```
● Estuary                                            ⌄
  5 assets · 3.40 km · about 1.5 ha
  2 of 5 left to spray (40%) · 1.80 km
```

- The **dot** is the most urgent asset in the block, never an average: one overdue line is not
  made up for by four that are not due.
- The **length and the area** are totals of the block, and each is left out when it is not
  known rather than shown as a zero — a block of troughs has no length, and saying "0 m" about
  it would read like a measurement. The area is called an estimate, and when only some lines
  record a swath width the tile says how many it came from.
- The **share left** is counted in assets, with the distance left beside it. Counting metres
  would let a block whose only due asset is a trough report "0% left" directly above a line
  saying something is left; the bar shows the work done in the dot's colour, so the two agree.
- Blocks are **closed to begin with**, ordered most urgent first and then by name. Assets in no
  block follow, in name order.

Blocks are made by typing a name into **Block or group** on an asset, and taken apart by
blanking it. Names are unique regardless of case, so "estuary" and "Estuary" are one block. The
field offers the blocks that already exist as you type, and says which of the two things the name
is about to do — *In the block "Estuary"*, or *Starts a new block called "Estuary flats"* —
because a misspelling used to be a new block with one asset in it and nothing anywhere saying so.

**Blocks**, from the asset list's top bar, is where they are kept tidy: rename one, write down
what it is for, or delete it. Deleting a block never touches the assets in it — they simply come
out of it, and the confirmation says how many, by name — and a block that nobody is in any more
is listed as holding nothing rather than quietly disappearing, because clearing it up is the
reason to open the screen at all.

## A line that was only part sprayed

Running out of spray halfway along a track used to leave a problem you could only find by
opening the record: the map drew the whole line in one colour, so a half-sprayed track looked
done and the other half could sit untouched until it was a fortnight overdue.

The map now colours each **stretch** of a line by its own last spray, using the same interval
and lead time the asset's own light uses, and the recording is what says which stretch that
was. Six things follow from that, and they are the whole behaviour:

- **Half sprayed today is half green and half red.** The recorded pass covers the stretch that
  was driven; the rest reads as still to spray, which is what it is.
- **A gap in the fixes is ground the pass drove.** The recording is read as the line it drew,
  not as the fixes it happens to hold: a fix rejected under trees, or a minute with no fix in a
  gully, is not a stretch that went unsprayed — the machine that crossed it sprayed it, and both
  the number and the colour count it. A pass walked with a fix every fifty metres used to read
  as a quarter of the line sprayed.
- **A pause is a break, and it stays red.** Pausing stops the fixes, so a paused pass leaves the
  same hole in the recording a dropped signal does — and they mean opposite things. The app
  writes the pause down when the button is pressed (`recorded_breaks`, kept in a backup too), so
  the ground the operator was stopped over stays red, and the recording's own line is drawn in
  two pieces rather than joined across the trip to the water tank.
- **Two halves on two days is a sprayed line.** Each stretch keeps its own date, so the half
  done a fortnight ago is still green rather than being reddened by a pass that happened not
  to drive it. A map that cries shortfall over work that was done is a map that stops being
  believed.
- **A spray logged by hand covers the whole line.** It has no fixes to disagree with, and
  logging it was the operator saying they did it. Without this rule, every track sprayed
  without a recording would turn red the day this existed.
- **The recording is the evidence, and it wins.** If a pass was recorded and the phone never
  came within the tolerance of the line, the line reads as still to spray - the same thing the
  recording's own screen says about it. A green line because somebody typed a number in is
  the failure this replaced.

Nothing about it is stored. The stretches are recomputed from the planned line against the
recordings whenever the map is drawn, so a line that has been edited since is cut up as it is
now: the same rule the coverage percentage has always followed.

Only the sprays that could still matter are read - a pass older than the asset's own interval
cannot change a colour, because whatever it covered is due again anyway - so a map with a
season behind it does not load a season of fixes to draw itself.

## A line that is sprayed twice

Half the tracks on the place are walked up one side and back down the other, and a road is done an
edge at a time. For those, one pass is not half sprayed: it is not sprayed at all. Read as a single
pass - which is what the app did until now - a track walked once went green, its traffic light went
out for four months, and the other side was never done.

So an asset can be told that it takes **two passes**, on the edit form, and how far apart those two
passes run if the operator knows. Everything else in the app is unchanged: a line that has never
been told otherwise is one pass, and behaves exactly as it always did.

What the app then claims is decided from the fixes, in this order:

- **Opposite directions.** Up one side and back down the other walks the same metres twice heading
  opposite ways. Direction needs no accuracy at all: consecutive fixes either head along the line or
  back along it, and five metres of sideways GPS wander does not touch that.
- **Opposite sides.** One side and then the other, both times the same way along, is two passes at
  two offsets from the line - but only where those offsets are far enough apart to be real. Three
  metres on a road is two strips the app can see; a metre apart on a knapsack track is inside the
  noise of a phone in a pocket, and there the app does not pretend.
- **The operator's word, when neither of those can tell.** Two passes the same way along, on sides
  too close together to separate, is the one case the fixes cannot settle. Finishing such a pass
  asks: *did you do both sides?* Yes writes the spray and closes the job; no leaves the line owing
  the other pass. The answer is stored with the pass it was given for (`recorded_sessions`,
  `bothSidesClaimed`, carried in a backup), so the question is asked once and not on every screen.

Four consequences worth knowing:

- **One spray record for the job, written when it is done.** A pass over a line that takes two is
  saved as a recording but records no spray, because there is no job yet to record: the asset's
  light stays as it was and the recorder says *one pass still to go*. When the second pass finishes
  it - in the same walk or a fortnight later - one spray is written, carrying the ground driven over
  both legs, and the line turns green then.
- **A line that is walked once is not a line that needs attention either.** Its colour stays on the
  last completed job's date, so a two-pass track sprayed 130 days ago and walked once today is due
  again - which is what makes the second side worth going back for.
- **Treated area counts both passes.** The second pass runs beside the first rather than over it, so
  a 1 km track walked twice with a 1.5 m knapsack treats 3,000 m², and the area in a block's total
  and in a handover says so.
- **The app cannot see the swath.** It can see that a line has been walked twice, in two directions
  or on two sides, and it will say that rather than claiming more. Two walks up the same side, a
  metre apart, with the two passes recorded as one run, read as two passes it cannot tell apart -
  so it asks, and the answer is what counts.

## A track with a side track

A track on the ground is not always one path: a fenceline has a spur into a gully, a lane has a
gateway you have to drive up and back. Drawn as one line, the only way to draw the spur was to walk
up it and back down it inside the line - and that costs twice. The spur's metres were in the length
twice, and the two-pass reading saw the same ground walked in opposite directions and called a dead
end *both sides done*, which was the right answer for the wrong reason.

So an asset's geometry is a **line and the side tracks hanging off it**. Path 0 is the line and the
rest are side tracks in the order they were drawn, which is the order the database keeps them in.

- **Draw the line to the junction, press *Side track*, tap along the spur, press *Back to the
  track*, carry on.** The side track leaves the track where the track currently ends, so its first
  vertex is the line's own last vertex - the same two numbers, not a copy, which is what makes it a
  join rather than two lines that happen to be near each other.
- **The length counts every path once.** A 222 m track with a 111 m spur is 333 m of ground if you
  drive it as two paths, not the 444 m walking the spur twice reads as - and that number is the
  handover figure and the coverage denominator.
- **Every path is coloured by its own spray.** A pass that drove the line and never went up the spur
  leaves the spur red, and the coverage percentage agrees with the colour, because both are worked
  out path by path.
- **A tap on the spur opens the track it hangs off.** The map draws one feature per path and both
  carry the asset's id.
- **A side track is one trip, not two sides.** A job with two sides is a *line*: up one side and back
  down the other. A side track is a strip you drive up and back in one go, so one trip up it dates it,
  and it never owes a second pass - which is the rule that stops a dead end reading as finished for a
  reason that has nothing to do with the spur. On the card, a side track counts as done once every
  stretch of it carries a date: the junction is shared with the line, so a pass along the line is
  always within tolerance of the first few metres of a spur leaving it.
- **One level only.** A side track hangs off the line; it has no side tracks of its own.
- **A GPX file carries them both ways.** A track with a spur is written as one `<trkseg>` per path -
  which is what a GPX track's segments are for - and a file whose segments each start on the first is
  read back as a track with that many side tracks. A file whose segments do not meet (a tool that cuts a
  line up for its own reasons) is read the old way, every point joined into one line, and the sentence
  says so rather than the import failing or the difference going unmentioned.

**Changing a track that is already drawn** is *Change the line* on the track's own page, which opens
the same drawing screen on the stored geometry. The camera opens on the **track** rather than on the
phone, because an operator changing a track is usually nowhere near it; the name, kind and block are
not on that screen at all, since those are the details form's; and Save writes the whole geometry at
once with the length it adds up to. Back without saving leaves the track exactly as it was.

Tracks drawn before any of this existed are exactly the line they were - the migration puts every
vertex on path 0 and changes nothing else - and a track that was drawn the old way with an up-and-back
spur stays a doubled line with an overstated length until somebody re-draws it with *Change the line*.
The app does not go looking for the shape of one: that would be guessing at ground the operator knows
better.

The desk can read a track's side tracks, because the drawing it fetches has one feature per path, and it
is **handed** them as data too (the record's `paths`, line first). From v0.6.31 it can also **draw one and
take one off**: with a track's shape open, *Side track* starts one where the track ends, the clicks and
traces go onto it, *Back to the track* hands the line back, and *Remove this side track* takes it off
again — the phone's own two moves, in the phone's own words. A spur drawn on a computer is a write like
any other: the phone judges it by the same rules as one drawn on the phone, and keeps it.

What cannot happen is a page losing a spur it never saw: a write carrying a **single path** for a track
that has side tracks is refused as a page that is out of date (*"Reload the page and try again"*), and a
write carrying `paths` is taken whatever number of them it holds. Changing the details of such a track
saves as it always did.

## Backup files

**Back up everything** writes one JSON file to wherever the operator chooses —
Downloads, a USB stick, an email — holding the whole of what the app knows: assets and
their geometry, spray events and the amounts that went out, the product catalogue, the
amounts each asset remembers, and every GPS recording with its fixes.

Ids are carried through in both directions, deliberately. An asset, its geometry, its
sprays and the recording that proves them are joined by id, so a restore that
renumbered them would produce a database that looks right and connects nothing.

**Restore from a backup** reads the file, shows what it holds beside what the app
holds now, and only then replaces. Replacement rather than merge, because half of one
season and half of another is not a state to discover in a paddock. It runs in a single
transaction, so an interrupted restore leaves the previous data untouched.

Downloaded offline imagery is deliberately absent: it describes tiles on one phone, and
the tiles can be fetched again. The switches travel, so a restored phone comes back set up
rather than factory-fresh — reminders, the update check, and where the off-site copy goes —
but the LINZ key and the GitHub token do not, because both are credentials rather than
records, and a backup file may be read by whoever finds it.

Files are versioned (`"format": "spray-day-backup"`, `"version": 2`). An older file
restores into a newer build, since missing fields fall back to defaults; a file from a
newer build is refused with an explanation rather than half-read. The switches arrived
without a version bump for the same reason: a file written before they existed simply has
no such key.

### The copy that is not on the phone

A file you keep still depends on you keeping it, and the phone holding the season is
exactly the thing that gets lost, wiped, or dropped in the creek. So the same backup is
written somewhere else as well, down the same seam: a `BackupTarget`, which is a file
(through the picker above) or a private GitHub repository.

**Where the copy goes** chooses between nowhere, a file, and a repository. The repository
is the one that keeps history without being asked: every backup is a commit, so the copy
from before a mistake is still there. Each phone writes its own file
(`spray-day-<model>.json`), so two phones pointed at one repository cannot overwrite each
other's copy, and a copy says which phone it came from.

The token is a fine-grained GitHub token with **Contents: read and write** on that one
repository. It is kept on the phone and never written into a backup — the copy lives in
the very repository the token can write to, and a file carrying the token would hand over
the repository with it. **Test** asks GitHub what the repository is and what the token may
do, and refuses a public one: a season of spray records is not a thing to publish.

One rule decides whether a copy is written at all: **an empty database is never written
over the copy.** After a wipe this phone holds nothing, and the copy off-site may be the
only record left — so the write is refused, the screen says so in as many words, and the
way forward is **Restore from the copy**. There is no override, because "my phone is
empty, so empty it shall be written" is not a thing anybody means.

Restoring from the copy is the same dialog as restoring from a file: both sides counted,
and counted again at the moment of confirming rather than remembered from when the list
was drawn, because which side is which is the only thing that has to be got right.

Unattended, the copy is written once a week (WorkManager, network required) and a failure
is said out loud at most once every three days — a backup failing quietly since spring is
the failure that costs a season, and a notification a day about the same expired token is
the one that gets turned off.

## Handover records

**Handover record (CSV)**, in the same Settings card, writes the season as a spreadsheet
for somebody else: one row per product per spray, oldest first.

| Date | Asset | Group | Method | Product | Amount | Unit | Water (L) | Distance (km) | Area (ha) | Operator | Notes | Recording |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

Three decisions worth knowing about:

- **One row per product, not per spray.** "1.5 L of Glyphosate on Home block on the
  14th" is the unit an auditor, a client or the next operator asks about, and it is the
  only shape that adds up in a spreadsheet.
- **ISO dates** (`2026-09-14 20:50`), because a spreadsheet sorts ISO and has to be
  told what "14 Sep 2026" means. And **numbers without units in them**: a cell reading
  `2.35 km` is text to a spreadsheet and cannot be added up.
- **Escaping is the part that must be right.** A block called `Home, north` or a note
  containing a quote must not shift every later column, which is worse than a visibly
  broken file because it still looks like data. That is what the tests here are about.

A UTF-8 byte-order mark is written at the front, which is what stops Excel turning
"M\u0101ori" into mojibake on Windows.

The record covers sprays, which is what a handover is checked against. The plans
themselves are in the backup file, and what is still due is on the map.

## Build

Requires JDK 17–23 (this project is developed on JDK 23; Gradle 8.13 cannot run
on the JBR 25 that Android Studio 2026.1.x bundles, so the daemon is pinned in
`~/.gradle/gradle.properties`). Android SDK with platform 36 and build-tools
36.0.0.

Add your LINZ Basemaps key to `local.properties` (gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
LINZ_API_KEY=your_key_here
```

Get a free standard-access key from <https://basemaps.linz.govt.nz> (no
registration; note that standard keys **expire every 90 days**). The key in
`local.properties` is only the default: the app takes a key entered at runtime
under **Settings**, so an expired key is fixed in the paddock, and the same
screen will tell you whether LINZ accepts it.

```bash
./gradlew assembleDebug testDebugUnitTest lint        # CI runs exactly this
./gradlew connectedDebugAndroidTest                   # needs a device/emulator
./gradlew assembleRelease bundleRelease               # needs signing config
```

## Release

Pushing a `v*.*.*` tag builds, signs and publishes a GitHub Release with the APK
and AAB attached. Signing comes from repository secrets:

| Secret | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the release keystore (`.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | key password |
| `LINZ_API_KEY` | baked into the release build as the default key |

Locally, the same values can live in a gitignored `keystore.properties`. Keep a
backup of the keystore: without it you cannot ship an update that installs over
an existing install.

Each release carries one APK per architecture plus a universal one, so a phone
downloads ~15 MB (arm64) rather than ~50 MB of native libraries for four
architectures it will never use. Take the `arm64-v8a` APK for any phone from the
last few years; the universal APK runs anywhere.

Pushing a tag like `v0.6.12` stamps `versionName 0.6.12` and `versionCode 612`
(major × 10000 + minor × 100 + patch), so release codes are predictable and
always increase — a locally built test APK can be installed over, and can itself
be replaced by, a release.

**The tag is what ships**: every push to `main` that changes more than the words is tagged,
so the code and the release never drift apart and what is on a phone is always a commit
that can be found by name. Take the next version in the sequence — patch + 1 — rather than
reusing one: a tag points at the code that was built from it, and a number that has been
published cannot be moved without lying about what somebody already has installed.

A code change does not wait for the next release: **it is committed, pushed and tagged as its
own version**, in that order, before anything else is started. Work sitting in the working
tree is work that cannot be found by name, and a fix that is only on this machine is a fix
the phone has not got — the version number is cheap, while a batch of changes shipped
together is a batch that has to be untangled together if one of them turns out to be wrong.

**A change to the documentation alone is not a release.** The README, and comments, are
committed and pushed straight away so that they are never only on this machine, but they
carry no version of their own: they go out under the next code release's tag, which is the
release they describe. Publishing APKs because a paragraph was reworded tells every install
there is a new version to download and tap through for nothing — the update check asks
GitHub for the newest release, and the file it would fetch would be the one already there.

```bash
git add -A && git commit -m "..."                          # one change, one commit
git push origin main                                       # code: tag it, docs: let it ride
git tag -a v0.6.18 -m "..." && git push origin v0.6.18     # ships the release
```

## Getting a new version

The app is not installed from a store, so nothing else will ever mention that a new version
exists. It asks GitHub for the newest release of itself once a day instead — WorkManager,
network required, and the switch in Settings turns it off — and says so with a notification
when there is one. The same check is on a button, so you can ask now rather than wait.

**Settings → Updates** shows which version this is, whether there is a newer one, and one
button that downloads it and hands it to Android's installer. The download is the APK built
for *this* phone's architecture: each release carries one per ABI plus a universal build,
and the universal one is about four times the size, so the size shown before you commit is
the size you get.

Three things the platform decides rather than this app:

- Android asks you to confirm every install, and needs **install unknown apps** allowed for
  Spray Day before it will even offer to. Settings says when that is missing, and has the
  button that goes to the screen where it is granted.
- Play Protect may scan a sideloaded APK before it installs, because it has not seen it
  before. That is Google's check, not this app's, and it happens after the download.
- An APK signed with a different key is refused by the installer, which is the protection
  against a tampered or substituted download rather than anything in this code. The same
  property means an update cannot cross between a debug build and a release build.

Worth knowing while developing: a debug build reports `versionName 0.1.0` unless it is
stamped (`-PversionName=0.6.2`), so it will always claim there is a newer version; and
being debug-signed it cannot install a release APK. Both show up as the installer refusing
rather than as a fault in the download.

## Data attribution

Basemap imagery is licensed **CC BY 4.0** and provided by LINZ. Every map screen
shows `LINZ CC BY 4.0 © Imagery Basemap contributors`, linking to LINZ's
[attribution page](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data).

OpenStreetMap data is © OpenStreetMap contributors, under the
[Open Database Licence](https://www.openstreetmap.org/copyright). Where the drawn map is chosen,
every map screen instead shows `© OpenStreetMap contributors`, linking to that copyright page —
the credit is never behind a toggle, which their guidelines require in as many words, and their
[tile usage policy](https://operations.osmfoundation.org/policies/tiles/) is what keeps offline
areas to imagery only.
