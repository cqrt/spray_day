# Spray Day

GPS track planning and spray records for set spray tracks — built for the way
rural tracks actually get sprayed: the same lines, roughly three times a year.

The point of the app is the map: open it and see at a glance **which assets are
due**, coloured green (not due), yellow (due soon) or red (overdue / never
sprayed).

## What it does

- **LINZ aerial basemap** (New Zealand), with the required attribution shown
  permanently, and **offline areas** you can download so the map still works
  where there is no reception. **Where you are** is a dot on it, ringed by the accuracy the
  fix was actually good to — a fix under trees is drawn as the uncertainty it is, and a fix
  with no accuracy is drawn as a dot with no ring, because unknown is not the same as small.
  A **locate** button puts the camera back on you, and is where the map asks for location
  permission — on a tap, not on the way in. The dot follows you while the map is open and
  stops when you leave the tab, so it costs nothing to keep on.
- **Settings** with the LINZ key field, so an expired key is fixed on the phone
  rather than by shipping a new build. **Check** asks LINZ about the key in force
  and reports LINZ's own answer — accepted, expired, or rate limited.
- **Assets** — tracks, roads and pieces of infrastructure, in one list: import GPX,
  draw them by tapping the map, or record them by driving the line. The drawing screen
  asks what you are making as you make it, so a fenceline is drawn as an infrastructure
  line and a trough is placed with a single tap. Each row carries a coloured icon of
  what the asset is, next to the colour that says when it is due.
- **Per-asset settings**: each asset carries its own spray interval (120 days is
  only the default), its **kind** (track, road or infrastructure), whether it is a
  line or a single spot, how it is sprayed (**boom** or **knapsack**, which offers the
  usual width for that method), the swath width for a treated-area estimate, a
  **block or group** to work it with (assets sharing one fold into a single tile in the
  list), and notes. The interval is what the traffic light uses,
  so a block sprayed on a shorter cycle turns yellow on its own schedule.
- **Spray records**: pick an asset, enter the products and the **mL of each**,
  save. The asset turns green and its history starts. Amounts are remembered per
  asset, so the next pass is a confirmation rather than a retype.
- **Due reminders**: a notification when an asset comes due, so a spray window is not
  discovered a fortnight late. Nothing repeats daily, and a line drawn this morning
  is not nagged about.
- **Handover record**: the season as a CSV — one row per product per spray, with the
  asset, its group and spray method, the amount, water, distance, and the recording
  that proves it. Dates are ISO so a spreadsheet sorts them, and the file carries a
  byte-order mark so Excel opens accented names correctly.
- **The map is the home screen**: every asset drawn in its traffic-light colour, and
  each kind drawn differently — solid for tracks, dashed for roads, dotted for
  infrastructure, and a circle for anything that is a spot rather than a path. A
  **part-sprayed track is drawn in parts**: what the pass covered in the colour it
  earned, and what is still waiting for a tank in red, so "half this line is left" is
  visible without opening anything. A tap
  on an asset opens it, and the tap radius follows the zoom, so it is tappable zoomed
  out over the farm as well as at spray height. The map does not rotate: north is up,
  which is one less thing to get wrong with gloves on.
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
  written to the database as it arrives.
- **Spraying while recording**: pick the asset, type the amounts as they go in,
  and watch a live **coverage percentage** of the planned line. Finishing saves
  the recording and the spray together, linked by session id, so the traffic
  light updates, the spray history carries the distance actually driven, and a
  line that was only part done comes back on the map in two colours.
- **GPX export** of any asset, shareable to QGIS/Google Earth/forestry tools.
- **Recordings browser**: every GPS recording kept as evidence, showing the line
  that was driven, the plan it was for, and how much of the planned line it
  covered. Deleting a plan never deletes the recording.
- **Product catalogue you can keep tidy**: rename a product without breaking its
  history, or archive one so it stops being offered without losing what was
  sprayed with it.

## How offline imagery works

The map never talks to LINZ directly. Tiles are served by a small HTTP server
inside the app, bound to `127.0.0.1`, which:

- serves a tile from the on-disk store when it is held, so downloaded areas —
  and imagery you have simply browsed — work with no reception;
- otherwise fetches it from LINZ and stores it on the way through, so ordinary
  use of the map builds up offline coverage;
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

Tiles are plain `{z}/{x}/{y}.webp` files under `filesDir/tiles`, not MapLibre's
offline database, which keeps them inspectable, resumable tile by tile, and
servable straight back to the map. That matters because MapLibre's own
downloader, pointed at LINZ's hosted style, pulled ~10x the needed tiles (3,175
resources for a ~304-tile area) by walking style sources the imagery layers
never use.

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
was. Four things follow from that, and they are the whole behaviour:

- **Half sprayed today is half green and half red.** The recorded pass covers the stretch that
  was driven; the rest reads as still to spray, which is what it is.
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

Pushing a tag like `v0.6.11` stamps `versionName 0.6.11` and `versionCode 611`
(major × 10000 + minor × 100 + patch), so release codes are predictable and
always increase — a locally built test APK can be installed over, and can itself
be replaced by, a release.

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
