# Drawing tracks from a computer

A web interface for drawing and editing assets — tracks, roads, fencelines, places — with a mouse
and a keyboard, because drawing them by tapping a phone is the hardest part of operating Spray Day.

**This file is a plan and a handover, not a record of work done.** When the feature ships, the
decisions below move into the README as a `## Drawing from a computer` section and this file goes
away.

## Where this stands

- **Phase 1 is built and shipped: the phone serves it, and the page exists.** `app/src/main/assets/web/`
  holds `index.html`, `app.js` and `style.css`, with MapLibre GL JS vendored (pinned 5.24.0, licence
  beside it, no CDN). The desk draws the phone's own imagery, the work in its due colours and its
  kinds' dash patterns, places as houses, the list grouped by block, a card per asset, and "Where is
  the phone?". Phase 1 is complete: the ten-minute Doze check was done with v0.6.23 — screen
  genuinely off, the phone held in deep idle (`mState=IDLE`), and the page still loading the work.
- **Phase 2 has begun: the desk writes, metadata first.** v0.6.23 ships `PUT /api/assets/<id>` — the
  card's details form, the version fingerprint, and the phone's own rules and sentences doing the
  judging. Geometry, making a track and deleting one are not in it yet.
- The last shipped work is **v0.6.23** (the desk's first write); before it v0.6.22 (the page), v0.6.21
  (the phone side of the desk view), v0.6.20 (the HTTP split) and v0.6.19 (map layer switches). The
  state written here was true when the file was written — **check it rather than trust it**
  (`git status`, `HEAD` against `origin/main`, `git tag --sort=-v:refname`), because a plan document
  that claims a clean tree is a plan document that can be wrong.
- The next version to tag is **patch + 1** of the newest tag: v0.6.23 → **v0.6.24**, Phase 2's
  geometry and delete.
- Update the *Progress* section at the bottom as each step is finished, so a third task could pick
  this up as easily as the second.

**Picking this up in a new task:** follow the rules in `.clinerules/`, read this file, then check the
state it claims before believing a word of it, and start at *Next action* below. When a step lands,
the phrase is **"update the plan document"**.

## The decision

**The phone serves the editor over the Wi-Fi (option A).** The browser on a laptop is a second view
of the phone's own database: the page, the style and the tiles all come from the phone, so there is
one copy of the data and **no sync at all**. Every write goes through the calls the app already
uses.

The alternative (option B — a static editor on GitHub Pages writing a plan file that the phone
reviews and applies) was designed in full and set aside. It needs identity for web-created assets,
tombstones, conflict fingerprints implemented in two languages, an apply-preview screen and a Room
migration, and its failure mode is silent geometry loss. It only pays for itself if drawing has to
work when the phone is not on the same Wi-Fi. Nothing built for A would be wasted if B were wanted
later: the same editor would sit behind a second storage adapter.

**Phased, read-only first**, so the server can be proven with no write paths in it at all.

## The facts that shape it

1. **The backup file cannot be the wire.** `GitHubBackupTarget.save` reads the current sha and then
   PUTs the **whole document** under `spray-day-<model>.json`. Anything a second writer put in that
   file is destroyed by the phone's next backup, with no error anywhere.
2. **A delete cascades into spray history.** `SprayEventEntity` has
   `ForeignKey(AssetEntity, onDelete = CASCADE)`; recordings survive, because
   `RecordedSessionEntity.assetId` is a plain nullable column. Delete is the one web operation that
   can erase a season's record.
3. **The app already runs an HTTP server** (`offline/LocalTileServer.kt`): a hand-rolled
   `ServerSocket` with a pure, unit-tested path parser, one licence-aware tile store per basemap —
   and a promise that is not to be weakened: *bound to loopback only, so nothing is exposed to the
   network*. The web editor is a **second** server, on the Wi-Fi, alive only while its switch is on.
4. **The app already has a GitHub client, a private backup repository and a token** — none of which
   this feature touches.

## The phases

| Phase | What | Version |
| --- | --- | --- |
| **Step 0** | Split the HTTP plumbing out of `LocalTileServer` so a second server can reuse it. Nothing else changes. | **v0.6.20 — shipped** |
| **1** | The desk view: the phone serves the editor; the map, the imagery, the asset list, due colours. **Read-only.** | **v0.6.21 (the phone) + v0.6.22 (the page)** |
| **2** | Editing: draw, place, move vertices, rename, metadata, delete/archive — through `AssetRepository`. | v0.6.23 |
| **3** | Desk conveniences: GPX drag-and-drop, snapping, multi-select, tracing, show-archived. | v0.6.24+ |

### Step 0 — the HTTP split (v0.6.20, shipped)

`LocalTileServer` owned its socket, accept loop, request-line parse and response writing. Those are
now `offline/HttpServer.kt` with a small route table; `LocalTileServer` keeps its routes and its
loopback-only guarantee; the coming `web/WebEditorServer.kt` becomes a second instance, bound to the
Wi-Fi address, sharing the tile handler and `TileSource` list.

**This was the riskiest edit in the plan** — it touches the tiles every map in the app depends on —
so it landed first, alone, with `offline/LocalTileServerTest` untouched and green (11 tests, not a
character changed) and with the app's own map proven by screenshot: the pre-change release shot and
the new build's shot are the same map, pixel for pixel.

Two things this step deliberately did *not* do, for whoever builds on it: there is no request body
and no keep-alive in `HttpServer` (whoever adds the first POST adds the body), and the route table
has no opinion about methods — a route judges the whole request, so a method check goes in that
route's `claims`. A route that wants a path without its query asks `HttpRequest.path`; an anchored
parser like the tile one judges `HttpRequest.target`.

### Phase 1 — the desk view (v0.6.21 the phone, v0.6.22 the page)

**What shipped as v0.6.21.** The desktop view needs a phone that can answer, and the phone is where
the answers live: assets, blocks, products, due colours, geometry and tiles. The endpoints above all
answer, behind a token that is required on everything and made fresh each time the switch is thrown.
The switch is one card on Settings, and the address under it is the address to open - token and all,
because the address *is* the password. Evidence, and the two decisions this half had to make that
this document did not cover (binding every interface rather than the Wi-Fi address, and the geometry
travelling in the GeoJSON rather than in the state), are in `build/verify/web-editor.txt`.

From the operator's side: Settings gains one card, **"Draw from a computer"**, with a switch, the
address (`http://192.168.1.23:8799/?k=7f3a…`), a Copy button, and one line: *"Your computer must be
on the same Wi-Fi."* On the laptop: the same aerial imagery the phone has (its own tiles, no LINZ
key in the browser, offline areas included), every asset in its due colour and its kind's line
style, places as houses, the list grouped by block, a read-only card per asset, and a locate button
that asks **the phone** where it is.

### Phase 2 — editing (v0.6.23 the desk's first write)

**What shipped as v0.6.23 is `PUT /api/assets/<id>` and nothing else**: one asset's own details, sent
from the card's form and written through `AssetRepository`. `POST /api/assets` and the geometry half
of `PUT` are not in it — a browser cannot make a track or move a vertex yet — and neither is
`DELETE`. What a write may reach is fixed in `web/WebEditorDocuments.kt` and nowhere else: assets,
blocks and the phone's own fix may be read, an active asset's own details may be changed through the
edit rules, and that is all. Sprays, recordings, due dates and archived assets are out of reach, and
the repository call means `polylineLengthMeters`, `groupIdFor` and the transactions stay Kotlin's.

**The version is a fingerprint, not a column.** `WebEditorVersion.of()` is a SHA-256 over exactly the
fields a desk may write, plus the id and the block name — so it moves when a *writable* field moves,
it needs no migration and no schema change, and a spray recorded while the form is open does **not**
make the next save stale, because the phone's due arithmetic is the phone's own business. A mismatch
is **409 "this was changed on the phone while it was open here"**. This overrules the plan's
"with the differing fields shown": when a field has moved, the useful sentence is that the card is
out of date, not a list of fields to argue with.

**The phone judges every field.** The form sends numbers as *text*, and `ui/AssetEdits` — the same
rules the phone's own edit screen uses — turns them into a row or refuses them, so a refusal is shown
in the phone's own words ("Days between sprays must be a whole number.") and not in a second
vocabulary invented by the page. The form's choices and hints come from the phone for the same
reason: `WebEditorChoices` carries the kinds, shapes, methods and passes out of the phone's phrase
tables, plus the block, swath and separation hints, inside the state document.

**Still to come — v0.6.24.** Geometry (draw, drag a vertex, click a segment to insert one, ⌫ removes
one, double-click or Enter finishes, Esc cancels, Ctrl+Z undoes with a local pure stack in `edit.js`
tested without a browser, snapping to the first vertex and to other assets), `POST /api/assets`, and
delete under the rule below. The phone's own map and list update as you work, because it is one
database and Room flows already push changes.

**Delete rule.** The browser is told what is attached and shows it: *"Delete Estuary road? 3 sprays
go with it; its recording stays."* If the phone reports sprays or recordings attached, the web may
only **archive** (`active = false`) and the real delete stays on the phone; if nothing is attached, a
mis-drawn track may be deleted from the desk.

## Files

**New — phone side**

| File | What it does |
| --- | --- |
| `offline/HttpServer.kt` | Socket, accept loop, request-line and header parse, response writing, route table. No app knowledge. **Landed in v0.6.20**; v0.6.23 gave it its first **request body** — read from the same buffered reader as the headers, `Content-Length` counted in **bytes** (counting characters would truncate a macron), 256 KB cap, `Transfer-Encoding: chunked` refused as 411, a body shorter than its own header as 400. |
| `web/WebEditorLink.kt` | LAN addresses from `NetworkInterface` (all candidates; no permission needed), the per-session token, the URL. Address picking is pure and unit-tested. **Landed in v0.6.21.** |
| `web/WebEditorServer.kt` | The routes, **bound on every interface** — the plan said the Wi-Fi address, but `adb forward` (which the checks use) only reaches loopback, and the token is the door either way — alive only while the switch is on. Fixed port 8799, next free port if taken. **Landed in v0.6.21.** |
| `web/WebEditorService.kt` | Foreground service, type `dataSync`, notification carrying the URL. `tracking/TrackingService.kt` is the pattern. **Landed in v0.6.21.** |
| `web/WebEditorJson.kt` | The state document. **Reuses `AssetRecord` / `GroupRecord` / `ProductRecord`** from `domain/backup` — the vocabulary that is already versioned and tested — wrapped with the view fields rather than growing a second asset shape. The geometry travels in `/api/assets.geojson` instead of in here, so it is served once. **Landed in v0.6.21**; v0.6.23 added the `version` on each record and the two answers a write can get (`saved` and `refused`), and `WebEditorChoices` — the kinds, shapes, methods and passes taken from the phone's own phrase tables, with the block, swath and separation hints, so the desk's form speaks the phone's vocabulary instead of inventing one. |
| `web/WebEditorEdit.kt` | What a desk's write may be, as data: `WebEditorEdit` (every field as **text**, plus the version the form was handed), `WebEditorEdits.apply()` delegating to `ui/AssetEdits` so the phone's own rules produce the phone's own refusals, `WebEditorRefusal` (MISSING 404 / STALE 409 / INVALID 400) and `WebEditorVersion.of()` — a SHA-256 fingerprint over exactly the writable fields, the id and the block name, so the version needs no column, no migration, and does not move when a spray is recorded. No database and no Android: the whole thing is unit-tested. **Landed in v0.6.23.** |
| `map/WebStyleJson.kt` | The style the page loads: the basemap raster source with the LAN tile URL, **plus the four asset layers using the very same ids as `AssetLayerIds`**, dashes from `AssetLineStyles`, colours from `AssetColors`, house pictures named as `PlaceIcons` names them, and a geojson source pointing at `/api/assets.geojson`. |
| `app/src/main/assets/web/` | `index.html`, `app.js`, `style.css`, `vendor/maplibre-gl.js`, `vendor/maplibre-gl.css`, `vendor/LICENSE-mapLibre`, plus `edit.js` in phase 2. Plain ES modules: the file you edit is the file that runs. **Landed in v0.6.22**, with two things worth knowing: `index.html` loads its own stylesheet, library and module **by script** rather than by tags, because every request the phone answers needs the token and a browser asks for a stylesheet with no query otherwise — the 403 looks like a page of unstyled text; and the page's own code is dead simple on purpose: it draws, it does not decide. |

**Changed**

| File | Change |
| --- | --- |
| `offline/LocalTileServer.kt` | Uses the extracted `HttpServer`; its routes and its loopback-only guarantee are unchanged, word for word. Its tile route and `/status` are now factory functions a second server can be handed, so the editor serves the same tiles from the same stores. **v0.6.20 and v0.6.21.** |
| `ui/screens/SettingsScreen.kt`, `viewmodel/SettingsViewModel.kt` | The "Draw from a computer" card: the switch, the address, Copy. No preference is stored for the switch — the address *is* the state, so a switch can never claim to be serving with nothing listening. **v0.6.21.** |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_DATA_SYNC` and the service. `INTERNET` is already there. No other permission. |
| `README.md` | A `## Drawing from a computer` section, and the vendored licence note. |

## Endpoints

- `GET /` + the static page from `app/src/main/assets/web/` — so the editor ships inside the APK,
  version-locked with the app, and needs no internet at all.
- `GET /api/state` — the active assets (kind, shape, method, block, interval, swath, passes,
  `lengthM`, `lastSprayedAtEpochMs`, **`dueStatus` and `dueAtEpochMs` from `DueCalculator`**), the
  groups, the products (read-only), the work's bbox, and the phone's last fix from
  `tracking/DevicePosition.kt` — better than the browser's own geolocation, which a browser blocks
  on an insecure origin anyway. The geometry is **not** in here: it travels once, in the GeoJSON
  below, as built for v0.6.21.
- `GET /api/assets.geojson` — what the style's source reads.
- `GET /api/style` — the style, including `"placeIcons"`: which pictures the page must draw and
  register, by the names the style asks for. The phone decides the picture; the page paints it (a
  canvas port of `map/HouseMarker.kt`'s house).
- `GET /tiles/<source>/{z}/{x}/{y}` — the same handler and the same stores the app's own map uses.
- `PUT /api/assets/<id>` — one asset's own details, the desk's first write. Every field arrives as
  **text** (`"intervalDays": "1"`), because a phone's fields are text: `WebEditorEdits.apply()` runs
  them through `ui/AssetEdits`, so both the rules and the sentences are the phone's. The body carries
  `version`, the fingerprint the card was handed; if it is not the one the phone holds, nothing is
  written. Answers **200** with the saved record and its fresh version (read back from the database,
  so the due colour in it is the phone's arithmetic *after* the edit), **400 `invalid`** with the
  field's own sentence, **409 `stale`** when the card is out of date, **404 `not-found`** for a number
  the phone does not have, an archived asset included. **411** for `Transfer-Encoding: chunked` and
  **413** over 256 KB are the socket layer's, refused before any route sees them.
- Still to come: `POST /api/assets`, the geometry half of `PUT`, `DELETE /api/assets/<id>`.
- **The token is required on everything**; anything without it is 403, including `/`.

## Defaults taken (overrule any of these and change this file)

1. **MapLibre GL JS, vendored** — pinned v5.x, its licence file beside it, no CDN. About 1 MB more
   in the APK, in exchange for a page that works with no internet and no third-party script running
   inside it.
2. **Fixed port 8799** with a fallback, so the address is bookmarkable. Optional `NsdManager`
   registration so `sprayday.local:8799` may also work (no dependency; `NsdManager` is in the SDK).
3. **Delete rule** as written under phase 2: archive-only once a spray or a recording is attached.
4. The web may **not** touch sprays, recordings, products or anything to do with due dates. It draws
   and edits assets. It is not a management application.

## What this does not touch

The backup path, the GitHub token, the off-site copy, the record tables, and the loopback tile
server's guarantee. **No schema change and no migration** in any phase — that is the payoff of
choosing A.

## How to verify

1. Install the debug build over a seeded database (`build/verify/db/seed*.py`, pushed with
   `adb push` + `run-as nz.mckenzie.sprayday`); flip the switch on. **On API 36 that push has to go
   through `/data/local/tmp` with `chmod 644` first — `run-as` cannot read `/sdcard` at all. The
   exact commands are in `build/verify/web-editor.txt`.**
2. `adb forward tcp:8799 tcp:8799` puts the phone's endpoint on this machine, so the real page can
   be driven against real data without a Wi-Fi dance. Screenshot the page and assert the same
   assets, the same due colours and the same geometry bbox the app's own map shows.
3. No token → 403; wrong token → 403; right token → the page.
4. Switch off → the port is closed, and `netstat` shows the loopback listener and the LAN listener
   as two separate sockets, proving the loopback promise is intact.
5. Screen off for ten minutes → the page still loads (foreground service, Doze).
6. Re-run the step-0 proof: the app's own map still draws its tiles.
7. A write is checked in the **database**, not in the page. Force-stop the app first so nothing is
   writing, then pull `databases/spray_day.db` *and* its `-wal` (`adb exec-out run-as … cat` into a
   file — Room's writes live in the WAL until something checkpoints it, and a copy without it reads as
   the state *before* the save), and read them with `build/verify/db/webcheck.py`. The claim is not
   "the name changed": it is "the name changed and **nothing else did**", which is why `webcheck.py`
   prints `lastSprayedAtEpochMs`, `lengthM`, `active` and the block beside every field, with the point
   counts and the spray count underneath the rows.
8. A **409** needs a race, and the page cannot be made to race by itself: open the card, then write
   the same asset's version-moved body from outside the page (a `fetch` with the page's own token is
   enough — `app.js` is an ES module, so its own `state` and helpers are not reachable from injected
   JavaScript), then press Save. The form must close, the work must reload, and the notice must be
   the phone's sentence about the card being out of date — with the typed value **not** in the
   database afterwards.

The page-side checks are driven, not eyeballed. `build/verify/db/pagedump.ps1 -Url <address> -Name <shot>`
opens a headless browser at the address, waits until the page has the work on it (a plain
`--screenshot` photographs the page before the phone has answered, and `--virtual-time-budget` is
ignored by this Edge), then writes the picture **and** the text of the list, the notice, the probes and
the browser's console beside the picture. `-Then "<javascript>"` does something to the page first —
clicking a row, clicking the locate button — and a run's `.txt` is often the faster read.
`build/verify/db/pageserve.ps1 -Port 8877` serves the real files out of `app/src/main/assets/web` with a
fixture farm behind them (`build/verify/db/page-fixture/`), for the corners a four-asset, block-less,
phone-less desk cannot reach. What it is not is the phone: its tiles are missing, so the imagery is the
style's background colour and the work draws on top of it.

## Progress

- [x] **Step 0** — `HttpServer` split out of `LocalTileServer`; `LocalTileServerTest` green and
      untouched; the app's own map still drawing imagery (screenshot). **v0.6.20.**
      Proven: 11 untouched wire tests plus 8 new `HttpServerTest` tests green, lint 0 errors with
      nothing naming the two files, the pre-change release screenshot and the new build's identical
      pixel for pixel over the map, tiles fetched and stored on the way through the refactored
      plumbing, and the **published** v0.6.20 APK installed and pixel-identical to the debug build -
      a minified build is not the debug build's claim. Notes in `build/verify/http-split.txt`.
- [ ] **Phase 1** — the desk view: the Wi-Fi server, the token, `/api/state`, `/api/style`, the page,
      the Settings card. Verified by the list above.
      - [x] **the phone side — v0.6.21.** Proven by payload: 403 with no token, 403 with a
            thirty-one-character prefix of the right one, 200 with it — `/api/state` (four assets
            carrying the same traffic lights the phone's own map shows), `/api/style` (the four layer
            ids of `AssetLayerIds` and the `placeIcons` names), `/api/assets.geojson` (the same
            features, the due colour in `stroke`, coordinates `[lng,lat]`), and a tile served through
            the app's own route and left in the app's own store. `netstat` showed the editor on 8799
            and the tile server on loopback only, as two separate listeners; the switch off closed
            the port and a request with the right token failed; the token was a different one after
            the switch was thrown off and on. Screenshots `web-card-off/-on2/-served.png`, notes in
            `build/verify/web-editor.txt`.
      - [x] **the page — v0.6.22.** `app/src/main/assets/web/`: `index.html`, `app.js`, `style.css`,
            MapLibre GL JS 5.24.0 vendored with its licence beside it, the house drawn on a canvas.
            Proven against the phone by pixel and by payload: the imagery drawn from the phone's own
            tile store, the same four assets with the same due colours the app's own map shows
            (amber `Due in 3 days`, red `Never sprayed` ×2, green `Due in 110 days`), the three line
            kinds in their own dash patterns, the place drawn as a red house by the page's canvas
            port of `HouseMarker.kt`, and the empty-farm list drawn from the phone's own state.
            Two bugs came out of it and both are fixed with a test or a border: the page's own files
            were being refused (403) because a browser asks for a stylesheet without the token, and
            `WebStyleJson` put `line-cap`/`line-join` in `paint`, which the Android SDK tolerated and
            MapLibre GL JS refuses outright — the whole style thrown away, a blank desk, and no tile
            ever requested. Blocks, the card after the map has opened, and "Where is the phone?" were
            driven against a fixture (`build/verify/db/pageserve.ps1`), because the seeded farm has no
            blocks and the emulator's System UI gave up ANR-ing before those two could be run against
            the phone. Screenshots `page-desk.png`, `page-card.png`, `fx-desk.png`, `fx-card.png`,
            `fx-locate.png`; notes in `build/verify/web-editor-page.txt`, which also says what was
            *not* verified and why.
- [x] **Phase 2, first slice — the desk's first write. v0.6.23.** `PUT /api/assets/<id>`: metadata
      only, through `AssetRepository`, judged by `ui/AssetEdits`, guarded by the `WebEditorVersion`
      fingerprint. Proven against the phone on a seeded four-asset farm (`build/verify/db/seedweb.py`),
      driven through the page's own form by `build/verify/db/pagedump.ps1 -Then`: a real save took
      Waima road's `intervalDays` from 120 to 1, and the desk redrew it as a red `2 days overdue` line
      while the app's own map went from *Overdue 2 / Due soon 1 / Not due 1* to **Overdue 3 / Due soon
      1 / Not due 0**; a refused field (`soon`) left the form open with the phone's own sentence under
      it; a save from a card whose version had moved under the page was refused in the phone's own 409
      words, the form closed and the work reloaded. The pulled database (`build/verify/db/after-web.db`
      **and its `-wal`**, read with `webcheck.py`) is the claim in numbers: one column of one row moved
      — `assets` id 3, `intervalDays` 120 → 1 — with every other field, every other row, the points, the
      blocks, the sprays and Room's own bookkeeping identical to the seed. Screenshots `web-desk.png`,
      `web-form.png`, `web-saved.png`, `web-refused.png`, `web-stale.png`, `web-place.png`,
      `web-phone-map-small.jpg`; notes in `build/verify/web-write.txt`. The **published** 0.6.23 APK
      was then verified on its own, over a clean install — the debug build is signed differently, so
      the seeded farm went with it and the release install was left empty, and nothing was pushed
      into it: a track drawn by hand on the phone, the desk served by the minified build, a save from
      its form answering "Saved. The phone has it.", and the phone's own detail screen then reading
      *0 sprays recorded · every 7 days* — so R8 keeps the serializers, the new fields and the write
      path (`rel-save-small.jpg`, `rel-save2.txt`). The ten-minute Doze check v0.6.22 left owing was
      done in the same sitting: screen off, `mState=IDLE`, and the page still loading all four
      assets (`web-doze.txt`). One bug came out of it:
      `#edit-buttons button` out-specified `#edit-save`, so the Save button was white text on a white
      pill until the rule named its parent.
- [ ] **Phase 2, the rest — v0.6.24.** Geometry (`POST /api/assets`, the vertex handles and their local
      undo stack in `edit.js`) and delete under the archive-only rule.
- [ ] **Phase 3** — desk conveniences: GPX drop, snapping, multi-select, tracing, show-archived.

Tick a box and add a line under it saying **how it was proven** — the point of this section is that a
summarised task, or a brand-new one, can see exactly where the work stopped.

## Next action

**v0.6.24 — geometry, then delete.** The desk can change what a track *is*; the next slice lets it
change where the track *goes*: `POST /api/assets` for a new one, drag a vertex, click a segment to
insert one, ⌫ to remove one, a local pure undo stack in `edit.js` so Ctrl+Z costs the phone nothing,
snapping to the first vertex and to other assets — and then the archive-only delete rule, with the
number of sprays that would go with it said in numbers before anything is written. Geometry goes
through the same version check as the details do, so a track moved on the phone cannot be overwritten
from a card that is out of date; that check is already built and tested, and the geometry slice only
has to carry it.

**Nothing is owing from v0.6.22 any more.** The map regression screenshot came with this slice
(`web-phone-map-small.jpg`), the ten-minute Doze check was done with it (`web-doze.txt`), and the
published 0.6.23 APK was installed and driven on its own once the tag had built (`rel-save2.txt`) —
which is the gate, because a minified build is not the debug build's claim.

