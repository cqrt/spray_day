# Spray Day

GPS track planning and spray records for set spray tracks — built for the way
rural tracks actually get sprayed: the same lines, roughly three times a year.

The point of the app is the map: open it and see at a glance **which tracks are
due**, coloured green (not due), yellow (due soon) or red (overdue / never
sprayed).

## What it does

- **LINZ aerial basemap** (New Zealand), with the required attribution shown
  permanently, and **offline areas** you can download so the map still works
  where there is no reception.
- **Settings** with the LINZ key field, so an expired key is fixed on the phone
  rather than by shipping a new build. **Check** asks LINZ about the key in force
  and reports LINZ's own answer — accepted, expired, or rate limited.
- **Tracks** as first-class objects: import GPX, draw them by tapping the map, or
  record them by driving the line.
- **Per-track settings**: each track carries its own spray interval (120 days is
  only the default), the boom's swath width for a treated-area estimate, a block
  or area label, and notes. The interval is what the traffic light uses, so a
  block sprayed on a shorter cycle turns yellow on its own schedule.
- **Spray records**: pick a track, enter the products and the **mL of each**,
  save. The track turns green and its history starts. Amounts are remembered per
  track, so the next pass is a confirmation rather than a retype.
- **Due reminders**: a notification when a track comes due, so a spray window is not
  discovered a fortnight late. Nothing repeats daily, and a line drawn this morning
  is not nagged about.
- **GPS recording** via a `location`-type foreground service, with every fix
  written to the database as it arrives.
- **Spraying while recording**: pick the track, type the amounts as they go in,
  and watch a live **coverage percentage** of the planned line. Finishing saves
  the recording and the spray together, linked by session id, so the traffic
  light updates and the spray history carries the distance actually driven.
- **GPX export** of any track, shareable to QGIS/Google Earth/forestry tools.
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
own tracks, and if neither is known the screen says so rather than quietly
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

- a track is mentioned when it first becomes due, again if it goes from *due soon* to
  *overdue* (that escalation is the news), and after that at most once a week while it
  stays due;
- a never-sprayed track is left alone until it is as old as the lead time: a line drawn
  this morning has not been missed, it has just been drawn;
- nothing is recorded as "already said" unless the notification actually appeared, so
  turning notifications on later does not find a week of reminders already spent.

Android 13 and later need `POST_NOTIFICATIONS`. Settings asks for it and says plainly
when it is missing, because a reminder system that is silently not permitted is worse
than no reminder system at all.

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

Pushing a tag like `v0.4.0` stamps `versionName 0.4.0` and `versionCode 400`
(major × 10000 + minor × 100 + patch), so release codes are predictable and
always increase — a locally built test APK can be installed over, and can itself
be replaced by, a release.

## Data attribution

Basemap imagery is licensed **CC BY 4.0** and provided by LINZ. Every map screen
shows `LINZ CC BY 4.0 © Imagery Basemap contributors`, linking to LINZ's
[attribution page](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data).
