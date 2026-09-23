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
- **Phase 2 is complete and shipped.** v0.6.23 was the desk's first write — the card's details form,
  the version fingerprint, the phone's own rules and sentences doing the judging. v0.6.24 added the
  geometry (`POST /api/assets` for a new track, the line through `PUT`) and `DELETE
  /api/assets/<id>?version=…`, with the desk's drawing in `edit.js` over `geometry.mjs`. v0.6.26 added
  **tracing** — a line followed with the button held down — and the first slice of Phase 3.
- **Side tracks reached the desk in v0.6.29**, from the phone's side of the work (see
  `side-tracks.md`): a line write may carry `paths` — the line and its side tracks after it — instead of
  one `points`, the record hands the page the paths to bring back (`WebEditorAssetRecord.paths`), and
  `wire.mjs` decides which of the two shapes a save has. **v0.6.31 taught the desk to draw one**, so the
  refusal that is left is about a *stale page*: a write carrying a single `points` for a track that has
  side tracks never read this track's paths, and a reload is what fixes it.
- A desk still cannot **archive** an asset, and now never will: that was dropped rather than deferred
  (see the phase 3 section), so what it may do with a track it wants gone is delete it when nothing is
  recorded against it, and be told the numbers when there is.
- The last shipped work is **v0.6.37** (the white edge under the picked-out line), before it v0.6.36 (the
  soft glow it replaced), v0.6.35 (the list's two new columns), v0.6.33, v0.6.31, the tracing in v0.6.26,
  and the phase 1 and 2 work before that. The state written here was true when the file was written —
  **check it rather than trust it** (`git status`, `HEAD` against `origin/main`,
  `git tag --sort=-v:refname`), because a plan document that claims a clean tree is a plan document that
  can be wrong.
- The next version to tag is **patch + 1** of the newest tag: v0.6.37 → **v0.6.38**.
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
| **2** | Editing: draw, place, move vertices, rename, metadata, delete/archive — through `AssetRepository`. | **v0.6.23 (metadata) + v0.6.24 (the line, a new track, the delete)** |
| **3** | Desk conveniences: GPX drag-and-drop, snapping, multi-select, **tracing a line over the imagery**. *Show-archived was dropped* — see below. | v0.6.26+ |

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

### Phase 2 — editing (v0.6.23 the desk's first write, v0.6.24 the line and the delete)

**Read this as the history of two releases**: the paragraphs below were written for v0.6.23 and describe
what that release did and did not reach; v0.6.24 added `POST /api/assets`, the geometry half of `PUT` and
`DELETE /api/assets/<id>` — the *Progress* section at the bottom has the shipped account of both.

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

**Shipped as v0.6.24 — the geometry, a new track, and delete.** `POST /api/assets` makes a track from a
line drawn on a laptop; `PUT /api/assets/<id>` grew a **`points`** field, so the row and the line travel in
one body and are written in one transaction; `DELETE /api/assets/<id>?version=…` takes a mis-drawn track
away. The line itself is drawn by `app/src/main/assets/web/edit.js` — handles, dragging, a click on a
segment to insert a vertex, ⌫ to take one off, a double click or Enter to finish, Esc to give up — over
`geometry.mjs`, which is pure and therefore runs under node: the undo stack behind Ctrl+Z, the tolerance a
click has to be inside, and the exact numbers a snapped vertex copies are all held by
`app/src/test/js/geometry.test.mjs`, which CI runs with `node --test`. A vertex is moved in the page's own
memory, undone from the page's own history, and **the phone is sent the finished line once** — an hour of
tidying a track is one write, and a drawing that is given up with Esc was never a write at all.

**The delete rule is not the one this plan proposed.** The plan said the desk might *archive*
(`active = false`) a track with sprays on it. It may not: nothing in the app ever writes `active = false`
and nothing reads it back, so an archive written from a laptop would be an asset that vanishes from every
list with no way to bring it back — a quiet, unrecoverable removal, which is the one thing a delete is not
allowed to be. So the desk may delete only what has **nothing recorded against it** — no sprays and no
recordings — and is refused otherwise, in the phone's own words and in numbers: *"Track 4" has 1 spray on
the phone, so it is not deleted from here. Delete it on the phone, where what goes with it can be seen
first.* The card shows that sentence before the button, and the button is off when the answer is no. The
recording is counted for a reason the schema makes sharp: `RecordedSessionEntity.assetId` has no foreign
key, so a recording outlives the asset it names and a delete would leave it pointing at a number nothing
answers to.

**Nothing about the drawing is decided in JavaScript.** The line is judged on the phone by
`domain/asset/AssetPathEdits` — consecutive repeats are dropped rather than refused, 2000 vertices is the
cap, a place is one point, a path is two or more, and every vertex has to be on earth — and the sentences
come from there. A body that says nothing about `points` leaves the line exactly as it is; a body that
sends an empty list is refused, because "drawn nothing" is not a way to empty a track. The version the card
quotes now covers the path as well, vertex by vertex, so a line drawn again on the phone refuses a card
that never saw the move rather than silently undoing it.


## Files

**New — phone side**

| File | What it does |
| --- | --- |
| `offline/HttpServer.kt` | Socket, accept loop, request-line and header parse, response writing, route table. No app knowledge. **Landed in v0.6.20**; v0.6.23 gave it its first **request body** — read from the same buffered reader as the headers, `Content-Length` counted in **bytes** (counting characters would truncate a macron), 256 KB cap, `Transfer-Encoding: chunked` refused as 411, a body shorter than its own header as 400. |
| `web/WebEditorLink.kt` | LAN addresses from `NetworkInterface` (all candidates; no permission needed), the per-session token, the URL. Address picking is pure and unit-tested. **Landed in v0.6.21.** |
| `web/WebEditorServer.kt` | The routes, **bound on every interface** — the plan said the Wi-Fi address, but `adb forward` (which the checks use) only reaches loopback, and the token is the door either way — alive only while the switch is on. Fixed port 8799, next free port if taken. **Landed in v0.6.21**; v0.6.25 made the token **nullable**, where null is a run that asks for nothing at all: the gate route claims nothing, and it is the operator's own switch on the card that decides which of the two a run is. |
| `web/WebEditorService.kt` | Foreground service, type `dataSync`, notification carrying the URL. `tracking/TrackingService.kt` is the pattern. **Landed in v0.6.21**; v0.6.25 reads the token switch at the start of a run and can **build the run again in place** (`ACTION_REFRESH`) when that setting changes — one intent rather than a stop and a start, which is a race a foreground service loses sometimes. |
| `web/WebEditorJson.kt` | The state document. **Reuses `AssetRecord` / `GroupRecord` / `ProductRecord`** from `domain/backup` — the vocabulary that is already versioned and tested — wrapped with the view fields rather than growing a second asset shape. The geometry travels in `/api/assets.geojson` instead of in here, so it is served once. **Landed in v0.6.21**; v0.6.23 added the `version` on each record and the two answers a write can get (`saved` and `refused`), and `WebEditorChoices` — the kinds, shapes, methods and passes taken from the phone's own phrase tables, with the block, swath and separation hints, so the desk's form speaks the phone's vocabulary instead of inventing one. |
| `domain/asset/AssetPathEdits.kt` | The rules a drawn line is judged by, on the phone: consecutive repeats dropped, 2000 vertices the cap, a place is one point, a path is two or more, every vertex on earth — and the sentences, in the app's own words, that come back when one of those is broken. No Android and no page: pure, and unit-tested. **Landed in v0.6.24.** |
| `domain/asset/AssetRemoval.kt` | `AssetRemovalRules.of(name, sprays, recordings)`: whether a desk may take an asset away, and the sentence saying why not — the counts, and a pointer at the phone where what goes with it can be seen first. **Landed in v0.6.24.** |
| `app/src/main/assets/web/geometry.mjs` | The drawing, as arithmetic: **the paths** (path 0 the line, the rest its side tracks), which one is being worked on, and the undo/redo stacks, the tolerance a click has to be inside, which vertex is under the cursor, where on the path a click belongs, the vertex to snap onto, the side-track moves (`startSideTrack`/`backToLine`/`dropSideTrack`), the whole track's metres, and GeoJSON in and out. No DOM, no map, no phone — which is why `node --test app/src/test/js/geometry.test.mjs` can hold the history behind Ctrl+Z, the junction's exactness and the `[lng, lat]` trap, and why CI runs it. **Landed in v0.6.24**; v0.6.26 added **tracing** — `TRACE_PX`, `metresPerPixel`, `trace` (the sampling rule), `simplify` (Ramer-Douglas-Peucker, tolerance on the ground) and `traced` (a whole stroke as one step of the history); v0.6.31 made the state **multi-path** (`createPaths`, `active`, `activePath`) and added the side-track moves and `pathsFeature`. |
| `app/src/test/js/geometry.test.mjs` | Those claims, under node: **26 tests**, no framework and no dependencies — `node:test` and `node:assert`. **Landed in v0.6.24; nine of them are tracing's, in v0.6.26.** |
| `web/WebEditorEdit.kt` | What a desk's write may be, as data: `WebEditorEdit` (every field as **text**, the version the form was handed, and `points` — the drawn line, absent when the write says nothing about it), `WebEditorEdits.apply()` delegating to `ui/AssetEdits` and `AssetPathEdits` so the phone's own rules produce the phone's own refusals, `create()` for a new asset judged against a blank row, `WebEditorRefusal` (MISSING 404 / STALE 409 / INVALID 400 / IN_USE 409) and `WebEditorVersion.of()` — a SHA-256 fingerprint over exactly the writable fields, the id, the block name **and every vertex of the path**, so the version needs no column, no migration, does not move when a spray is recorded, and *does* move when the line is drawn again. No database and no Android: the whole thing is unit-tested. **Landed in v0.6.23; the path and `create()` in v0.6.24.** |
| `map/WebStyleJson.kt` | The style the page loads: the basemap raster source with the LAN tile URL, **plus the four asset layers using the very same ids as `AssetLayerIds`**, dashes from `AssetLineStyles`, colours from `AssetColors`, house pictures named as `PlaceIcons` names them, and a geojson source pointing at `/api/assets.geojson`. |
| `app/src/main/assets/web/` | `index.html`, `app.js`, `style.css`, `vendor/maplibre-gl.js`, `vendor/maplibre-gl.css`, `vendor/LICENSE-mapLibre`, and from v0.6.24 `edit.js` (the handles, the drags, the keys) with `geometry.mjs` (the arithmetic), `wire.mjs` (which of the two drawing shapes a save carries), `glow.mjs` (the mark under a picked-out asset, v0.6.36) and `camera.mjs` (the remembered view, v0.6.38) beside them. Plain ES modules: the file you edit is the file that runs, and the drawing's arithmetic is a file node can run too. `index.html` loads its own stylesheet, library and modules **by script** rather than by tags, because every request the phone answers needs the token and a browser asks for a stylesheet with no query otherwise — the 403 looks like a page of unstyled text; `edit.js` asks for `./geometry.mjs?k=…` for the same reason. The page's own code is dead simple on purpose: it draws, it does not decide. **Landed in v0.6.22, the drawing in v0.6.24.** |

**Changed**

| File | Change |
| --- | --- |
| `offline/LocalTileServer.kt` | Uses the extracted `HttpServer`; its routes and its loopback-only guarantee are unchanged, word for word. Its tile route and `/status` are now factory functions a second server can be handed, so the editor serves the same tiles from the same stores. **v0.6.20 and v0.6.21.** |
| `ui/screens/SettingsScreen.kt`, `viewmodel/SettingsViewModel.kt` | The "Draw from a computer" card: the switch, the address, Copy. No preference is stored for the switch — the address *is* the state, so a switch can never claim to be serving with nothing listening. **v0.6.21**; v0.6.25 added **"Only this address can open it"** — the token as a preference (on by default, `web_editor_token_required`), which serves the run again when it is changed while serving. |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_DATA_SYNC` and the service. `INTERNET` is already there. No other permission. |
| `data/AssetRepository.kt` | `insertAsset(asset, geometry, groupName)` — a new asset and its line in one transaction, the id dropped so the database issues it and the length worked out from the vertices; `saveAssetEdits(asset, blockName, geometry)` gained an optional line written with the row; `allAssetGeometry()`, `recordingCountFor` and `recordingCounts()` for the documents. **v0.6.24.** |
| `data/db/AssetDao.kt`, `data/db/RecordingDao.kt` | `allGeometry()` (every vertex in one query, for the two documents that are built for the whole farm at once), and `countForAsset` / `assetIds()` — what a delete would take with it. **v0.6.24.** |
| `web/WebEditorDocuments.kt` | `create` and `remove` beside `save`, the path passed into the transaction, `removal` on each record, the phone's own `newAsset` defaults in the state document, and `mjs` in the content-type table — a browser refuses a module whose type it does not read as JavaScript, and the desk's drawing module is the same file node imports, so the extension is what tells both of them. **v0.6.24.** |
| `README.md` | A `## Drawing from a computer` section, and the vendored licence note. |
| `.github/workflows/ci.yml` | `node --test app/src/test/js/geometry.test.mjs` beside the Gradle gate: the desk's drawing arithmetic, held by the same file the browser runs. **v0.6.24.** |

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
- `POST /api/assets` — a new track or place, drawn on the desk. No `version`: there is no row yet for one
  to describe, and nothing to be stale against. The body is the same shape as `PUT`'s, with `points`
  **required** — a new asset with nothing drawn is refused by `AssetPathEdits` in its own words, and the
  id and the date are the phone's, not the laptop's. The body's block name starts a block if it is a new
  one, exactly as the phone's own form does. Answers **201** with the created record — 201 rather than 200
  so a page can tell "the phone has it now" from "the phone still has it" — **400** with the sentence for
  anything it will not take, **409** only if a block name lost a race.
- `DELETE /api/assets/<id>?version=…` — a track or place with nothing recorded against it. The version
  travels in the **query** because a delete has no body: a request that does not say which version it read
  is refused as unreadable rather than taken as "delete whatever is there". Answers **200** with the
  sentence saying what is gone, **409 `stale`** when the row moved under the page, **409 `in-use`** when
  sprays or recordings are attached — with the counts in the message and a pointer at the phone — and
  **404** for a number the phone does not have.
- The geometry half of `PUT`: the body may carry `points`, a list of `{lat, lng}`, and the line is written
  in the same transaction as the rest of the row. Absent means "the line is unchanged"; an empty list is a
  refusal. `points` is also in each record's `version`, so a stale card about a moved line is refused.
- **The token is required on everything**; anything without it is 403, including `/`. A run served
  with the token switch off asks for nothing at all: no gate, no token in the address, and none in
  the URLs the style hands to MapLibre (v0.6.25 — see *Defaults taken*, 5).

## Phase 3 — the desk conveniences

**Snapping shipped with the drawing itself** (v0.6.24, `geometry.mjs`, with a test for the loop that
closes): a new point snaps onto the other assets' vertices and onto the line's own first, so a track
comes back to where it started exactly rather than nearly.

**Tracing shipped in v0.6.26.** Holding the mouse button down and moving follows the pointer, so a
boundary that is *already drawn on the ground* - a fenceline, a paddock edge, a track on the imagery -
is followed rather than guessed at one click at a time. The two ways of laying a line mix freely: click
for the corners you know exactly, trace the runs between them, and both go onto the same line in the
order they were said. The arithmetic is `geometry.mjs`'s and is tested under node:
  - a point is sampled once the pointer has moved `TRACE_PX` (4) pixels - the hand's wobble is not the fence;
  - the stroke is simplified (Ramer-Douglas-Peucker) to what the operator could *see*, the tolerance
    being half a sample step worked out from the view they traced in (`metresPerPixel`), so a corner
    survives at full sharpness and the wobble along a straight run goes;
  - **the whole stroke is one step of the history**: Ctrl+Z takes the fence back, not one sample of it;
  - a press that never moved is not a step at all, so the click that follows it still means what a click
    has always meant.

**Side tracks, from v0.6.29, drawn from v0.6.31.** The phone's side of that work is `side-tracks.md`;
what matters here is the wire and the page. A line write carries **one of two shapes**: `points` (one
path, what every page before this sent) or `paths` (the line first, then its side tracks). The state
document's record carries the same paths, so a page has them to hand back — and from v0.6.31 the drawing
the page holds *is* those paths, so what it hands back is what it drew rather than a record it merged a
line into. `wire.mjs` is the one function that decides which shape a save has, and it is pure so that node
tests it (`app/src/test/js/wire.test.mjs`); `app.js` imports it with the token on the URL, as it does
`geometry.mjs`.

**A body carrying both drawings is refused.** A drawing whose path *count* differs from the track's is
taken, because the desk draws side tracks itself now: the paths it sends are judged by the app's own rules
(`AssetPathEdits` — every path whole, every side track starting on a vertex of the line), and a spur drawn
in a browser is a write like any other. The one thing still refused is a body of a single `points` for a
track that has side tracks: that page never read this track's `paths`, so it is out of date — and writing
it would drop every spur. It says *"Reload the page and try again"*, which is what fixes it.

**The page's drawing is the paths** (`geometry.mjs`), path 0 the line and the rest its side tracks, with
`active` saying which one the clicks, drags and traces go to — the same shape the phone stores and the wire
carries, so nothing is re-interpreted at either end of a save. The two moves the phone's own screen has are
here too, in its words: **Side track** (`startSideTrack`) and **Back to the track** (`backToLine`, which
drops one that never got a second point), with **Remove this side track** for second thoughts and `B`/`L`
doing the same from the keyboard.

**A side track can be taken hold of, since v0.6.33.** The handles, the delete key and the drags belong to
**the path in hand** — and the only thing that used to put a side track in hand was starting a new one, so a
saved side track had no gesture pointing at it and its points could not be moved or deleted. Now a click on
another path (`otherPathAt` + `hold`) takes hold of it and changes nothing about the drawing: the side track
gets the handles, Del takes its points off, a drag moves them, and *Remove this side track* takes the whole
strip. Taking hold is not a step of the history, so Ctrl+Z still takes back the last *change*. The cursor
says which of the three a click will do — grab over a handle, pointer over another path, crosshair on bare
ground — and the box in the map's corner says which path is in hand, with *"click a side track to work on
it"* while the line is.

**The junction is picked by clicking the track** (v0.6.32). A click on the line puts a point in it — which it
always did — and that point becomes where a side track will leave it, drawn as a filled dot and said in the
box. `startSideTrack(state, junctionIndex)` takes the vertex, or the line's end when there is none (a track
being drawn has nothing to choose, and an Undo can take the picked vertex away); the junction is read off the
line's own vertices every time, so dragging one takes any side track hanging off it, and taking the vertex
off the line takes the strip with it. A spur off the middle does **not** split the line: it hangs off it and
the line carries on from its own end.

**The page's words follow the app's** (v0.6.39). Reported as *"Draw a new track should be Draw a new asset,
Find a track or block should be Find an asset or block; check the app for other corrections"*. The desk had
been calling everything a track, which the app itself stopped doing when an asset became a thing that can
be a track, a road, a fenceline or a place. So the desk now says **Draw a new asset**, **Find an asset or
block** and *No asset or block matches that*, and the furniture has nothing made of *track* left in it. It
also picked up the phone's own words in three places where the phone had better ones: the form for a drawn
line is titled **New asset** (it was *New track*), and the card's two ways on are the phone's own menu
items, **Edit details** and **Change the line** (they were *Change the details* and *Change the shape*),
with that form titled *Edit <name>*. Nothing on the phone changed: the app is where the words come from,
and a word that is the phone's own cannot drift.

**The drawing's own furniture is a box in the map's corner** (v0.6.39). It used to be a bar across the top
of the page, and it grew a sentence for every thing the drawing learned to do until it was a paragraph over
the work being drawn. It is now a box the size of a map control in the map's own top-left corner: the
phone's own title for the job (*Draw a line* / *Change the line*), the phone's own counts (*4 points ·
3.89 km · 1 side track*), one short line about whatever the drawing is in the middle of (*Working on the
side track*, *Following the pointer - let go to put it down*, *A side track will leave the line here*), and
then the keys as **pairs** — *Click · a point goes in*, *Del · take the last one off*, *Esc · give up* —
with the three buttons under them. The Ctrl+Z row greys out while there is nothing to take back, which is
what the old bar's *"nothing to take back yet"* said in six words. The words are still the page's own,
because none of this is a rule: the phone's words are for what the phone decided.

**"Put away" was dropped** (the decision). The plan had archiving as this phase's *show-archived*, and
`AssetRemovalRules` said it was waiting for a screen that showed an archived asset. The operator owns
the data, was asked directly, and answered the other way: there is no archive, so there is nothing for a
screen to show. What the desk may do with a track it wants gone is unchanged - delete it if nothing is
recorded against it, and if sprays or recordings hang off it, they are named in numbers and the phone is
where it goes. The `active` column stays exactly as it is: it is the record's own field, a backup
carries it, and the state document filters on it. Nothing sets it, and nothing is going to.

## Defaults taken (overrule any of these and change this file)

1. **MapLibre GL JS, vendored** — pinned v5.x, its licence file beside it, no CDN. About 1 MB more
   in the APK, in exchange for a page that works with no internet and no third-party script running
   inside it.
2. **Fixed port 8799** with a fallback, so the address is bookmarkable. Optional `NsdManager`
   registration so `sprayday.local:8799` may also work (no dependency; `NsdManager` is in the SDK).
3. **Delete rule** as written under phase 2: archive-only once a spray or a recording is attached.
4. The web may **not** touch sprays, recordings, products or anything to do with due dates. It draws
   and edits assets. It is not a management application.
5. **The token is required, and it is a switch rather than a law** (v0.6.25). On by default, so an
   install that never touches the card is served exactly as it always has been: the address carries
   a per-run secret and everything without it is 403, the page included. It can be turned off, and
   what that is *for* is worth writing down, because the argument for the token is not "the internet"
   — a router does nothing about a visitor's phone on the same Wi-Fi, a device in the ute, or a web
   page open on the operator's own computer reaching a port that can now draw, change and delete
   tracks. On a home network where every device on it is the operator's own, that secret buys them
   little and costs them a paste, so it is theirs to switch off; the card says what it gives away, in
   words, and the address it hands out is the bare one that says the same thing. Changing it while
   the editor is serving builds the run again, so the address on the card is always the address that
   works. An empty token is not offered, and a run with no token is a *state* rather than a missing
   value: `WebEditorServer`'s token is null and its gate claims nothing.
6. **Where the desk was looking is kept on the desk** (v0.6.38). A refresh, and a basemap switch on the
   phone, both build the page again, and an operator working one corner of the farm should not have to
   find it again on every basemap — so the camera is written to the *browser's* own store and comes back
   as the map's opening options. Not to the phone: where a particular desk is looking is that desk's own
   business, two laptops on the same phone are two views, and the phone's own map has a camera the page
   has no business moving. This is the desk's one write that is *not* an asset, and it stays on the
   machine the operator is sitting at — the store holds five numbers (centre, zoom, rotation, tilt) and
   nothing else, no token and no asset, and it is read by `camera.mjs`, which refuses anything it could
   not open the desk on rather than showing the operator blank ocean.

## What this does not touch

The backup path, the GitHub token, the off-site copy, the record tables, and the loopback tile
server's guarantee. **No schema change and no migration** in any phase — that is the payoff of
choosing A. The phone's database is written by the desk only through the four writes above; the
remembered view (default 6) is the browser's own store on the operator's machine and never reaches the
phone at all.

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
- [x] **Phase 1** — the desk view: the Wi-Fi server, the token, `/api/state`, `/api/style`, the page,
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
- [x] **Phase 2, the rest — the line and the delete. v0.6.24.** `POST /api/assets`, the geometry half of
      `PUT`, `DELETE /api/assets/<id>?version=…`, and the desk's drawing in `edit.js` over `geometry.mjs`.
      Proven against the phone on a seeded farm, driven with real browser input
      (`pagedump.ps1 -Mouse`, which now sends mouse and key events through the DevTools protocol and can
      read the drawing handles off the page's own picture): three clicks and Enter made a track — asset 5
      `Desk drawn`, TRACK LINE UNSET, three vertices, **270.36 m** worked out by the phone; a drag moved
      vertex 0 and the phone's own length went **683.21 → 665.27 m**; **Ctrl+Z** took the step back and the
      phone's points were *unchanged* — the undo cost it nothing, which is the claim in the plan; a click on
      the line inserted a vertex **between** two, and the phone then held four; a delete of a track with
      nothing on it answered *"Desk drawn is gone from the phone."* and the pulled database showed the row
      and its points gone; and a delete of a track with a spray on it was refused **409 `in-use`** with the
      counts in the phone's words, the button disabled before anyone pressed it, and the asset left alone.
      Screenshots `web-geo-draw.png`, `web-geo-drag3.png`, `web-geo-undo.png`, `web-geo-insert.png`,
      `web-geo-delete.png`, `web-geo-refuse.png`; notes in `build/verify/web-geometry.txt`. Two real bugs
      came out of the driving, both in the new page code: a click handler that threw
      `Expected [x, y] or {x, y} point format` on `map.unproject(event.point)` — this MapLibre build's
      event carries `lngLat` already, and the page now reads that instead of asking the map to work the
      same position out twice — and a drag that had to take hold of a handle the picture had found,
      because a press a few pixels off one pans the map and looks exactly like a drag that did nothing.
      The **published** 0.6.24 APK was then verified on its own, over a clean install (the debug build is
      signed differently, so nothing was carried over and nothing was pushed in): a track drawn by hand on
      the phone, the desk served by the minified build drawing a new track from scratch (*"Saved. The phone
      has it."*), dragging a vertex of the hand-drawn line — RelGeo's first vertex moved 90 km south-east on
      the laptop and the phone's own screen then read **Length 146.40 km** where it had read 198.93 km, with
      its own map drawing the bend — and deleting a track with nothing on it. The state document from the
      shipped artifact carried `removal` and `newAsset`, so R8 keeps the new fields and serializers
      (`rel-geo-draw.png`, `rel-geo-drag2.png`, `rel-geo-delete.png`, `rel-geo-phone-small.jpg`).
- [x] **The token is a switch rather than a law. v0.6.25.** The "Draw from a computer" card gained a
      second switch — **"Only this address can open it"** — on by default, and the address itself says
      which way it is set: with it on the address ends in a per-run secret and every request without
      one is 403, the page included; with it off the address is bare (`http://10.0.2.16:8799/`), and
      anything that can reach the port can read the work, draw a track, change one and delete one.
      Turning it while the editor is serving builds the run again, so the card never shows an address
      that does not work. Proven on the emulator by payload, screenshot and the phone's own screen:
      with the token on, `GET /api/state` was **403 "no token"** and 200 with the secret; switching it
      off while serving changed the address to the bare one *in place* (the run was rebuilt), answered
      **200 with no token**, and a **POST** of a new track with no token was **201** — the phone's own
      list then read *Bare door track · 1.39 km · never sprayed*, worked out by the phone, and the same
      **DELETE** with no token was 200 and the list went back to *No assets yet*. Switching it back on
      gave a **new** secret (so the run really was rebuilt) and 403 without it again, and the page
      itself was 403 at the bare address. The desk was loaded over the bare address by
      `pagedump.ps1 -Url "http://127.0.0.1:8799/"`: every request a plain URL with no `k=`, all 200,
      the map drawn and the list showing the track — and over the token address with the secret. The
      setting survived a reboot (`token-bare2.png`), and turning the editor on with it off served the
      bare address from the first moment. **One real bug came out of this**: `index.html` had its own
      copy of the app.js check that refuses a page with no token, so a bare address loaded a page that
      said *"There is no token in this address"* and never booted — three files had to learn that a
      page which was served at all has already been let in. Tests: `WebEditorServerTest` (a run with no
      token serves the page, the documents, the tiles and all three writes, and does not care what
      token you bring), `WebEditorLinkTest` (no token, no `k=` in the address),
      `WebEditorSaveTest` (the style carries the token when there is one and nothing when there is
      not), `SettingsViewModelTest` (the switch is written and only serves again while it is serving).
      585 unit tests, 169 instrumented, lint 0 errors. Screenshots `token-card-off.png`,
      `token-on.png`, `token-bare.png`, `token-bare2.png`, `bare-door-phone.png`, `bare-door-desk3.png`,
      `guarded-bare.png`, `guarded-token.png`; notes in `build/verify/web-token.txt`. The **published**
      0.6.25 APK was then installed clean and driven on its own: a fresh install showed the switch on
      by default, 403 without the token and 200 with it, the bare address in place when it was turned
      off, a track POSTed with no token appearing on the phone's own screen (*Published bare track ·
      1.39 km · never sprayed*), the minified desk drawn over the bare address, the delete answered,
      and a new secret when it was turned back on (`rel-token-on`, `rel-token-bare`, `rel-token-back-on`,
      `rel-bare-desk.png`, `rel-bare-phone-small.jpg`).
- [x] **Phase 3, first slice — tracing a line over the imagery. v0.6.26.** Holding the button down and
      moving follows the pointer, and the stroke is put on the line when the button comes up: `trace`
      samples every four pixels of travel, `simplify` (Ramer-Douglas-Peucker, tolerance from
      `metresPerPixel` and the zoom that was traced in) keeps the corners and drops the hand's wobble,
      and `traced` makes the whole fence **one** step of the history. Proven against the phone by driving
      real mouse events (`pagedump.ps1 -Mouse`, which grew a `trace` step for this): **57 mouse samples
      along eight waypoints arrived as eight vertices**, the phone's own screen then read *Traced fence ·
      607.13 km · never sprayed* (the length is the phone's arithmetic, and the emulator's map is zoomed
      right out), and **one Ctrl+Z took the whole traced fence back** - 0 points, and on an asset the
      phone already had, tracing a second fence (8 → 27 points) and pressing Ctrl+Z once left **the stored
      line identical to the byte** after the save that followed (*"Saved. The phone has it."*, 410 bytes
      before and after). One run in five put nothing on the line for the first trace and the identical
      gesture worked either side of it, which is the racy first fit below costed as a wasted run. Nine new
      node tests (26 in all) pin the sampling, the simplification, the corner at full sharpness, the
      one-step history, the press that is not a trace and the loop that closes. Screenshots `trace-a.png`
      (the line), `trace-b.png` (after one Ctrl+Z), `trace-c.png` (saved), `trace-phone.png` (the phone's
      own list); notes in `build/verify/web-trace.txt`. The **published** 0.6.26 APK was then installed
      clean and driven on its own: the editor on (token switch on, as a fresh install has it), *Draw a new
      track*, a fence traced through five waypoints - 41 mouse samples arriving as **4 stored vertices** -
      saved, and the phone's own screen reading *Published trace · 481.95 km · never sprayed*, the same
      number the API answered (`rel-trace-desk.png`, `rel-trace-phone-small.jpg`).
- [x] **Phase 3's side tracks landed in v0.6.31** — *Side track* and *Back to the track* on the drawing
      bar, the page's state holding the paths, and the phone taking a spur the desk drew. Proven by
      driving the desk in a browser (headless Edge over the DevTools protocol,
      `build/verify/db/deskdrive.mjs`): the bar read *4 points · 3.89 km · 1 side track* with the track's
      shape open, *5 points · 3.89 km · 2 side tracks* the moment a side track was started, *6 points ·
      4.28 km · 2 side tracks* after a click drew one — **the new spur counted once** — and the save PUT
      three paths, line first, no `points` field. The buttons were in the right two states at each step.
      What that run did **not** prove is the canvas: headless Edge here paints the style's background and
      nothing else, so the screenshots (`desk-side-track-*.png`) are no evidence of a drawing, and that
      claim rests on `pathsFeature`'s node test rather than on pixels. 46 node tests (15 new), 189
      instrumented (2 new, 1 replaced), 619 JVM. Notes in `build/verify/desk-side-tracks.txt`.
- [x] **The junction became a choice in v0.6.32** — reported as "I click beside a section of track and the
      spur starts from the far end, miles away". Clicking the track now picks the point the side track will
      leave from, shown as a filled dot and said in the bar, and the spur's first vertex *is* that line vertex
      to the bit. Proven by the saved body from the browser driver: the new path's first vertex was
      `-41.499995…,173.92` — the point clicked, in the middle of the line — where the track's end is
      `173.94`. 51 node tests (5 new). Notes in the same `build/verify/desk-side-tracks.txt`.
- [x] **Editing a side track's own points landed in v0.6.33** — reported as "can delete and move points on a
      main track but not on side tracks". A click on another path now takes hold of it (and changes nothing
      about the drawing), so a side track gets the handles, Del, the drags and *Remove this side track*; the
      cursor says which of the three a click will do and the bar says which path is in hand. Proven in the
      browser driver: taken hold of by clicking it, Del-ed down to one point (where it comes off whole, as the
      phone refuses a path of one), Ctrl+Z back exactly, and a drag that changed the length — the saved body
      showing the junction moved onto the line's own vertex, to the bit. 56 node tests (5 new).
- [x] **The list's two new columns. v0.6.35** — asked for in one line: "In the track list, 2 new columns, for
      spray method and passes amount". A row is read across four columns now, under the words that name
      them: the name, the spray method, the passes to finish it, and the due date. The method's words come
      out of the state document's own phrase table (`state.choices.methods`, which is `MethodPhrase` on the
      phone), so the page's own copy of the three methods is gone - and the card says "Not recorded" where
      it said "Not set", the phone's word for it. The panel went from 340 to 460 px because four columns
      need the room, and the three columns after the name are fixed widths: a row is its own box, so a cell
      as wide as its own word would put every row's method somewhere different. Proven on the emulator over
      six seeded assets covering every state the two columns can hold (`db/seedlist.py`): the page's rows
      matched `/api/state` asset by asset, the words row and all six rows had their four cells at the same
      left edges (12, 171, 277, 339), and per-column pixel counts in the picture said the same (the method
      column's ink starting at 172 in every row, the passes digit right against its own column's edge). The
      word over each column sits above the list rather than in it, so it stays put while the work scrolls
      and stays out of the live region; it is hidden when nothing matches. The **published** 0.6.35 APK was
      then driven on its own over a clean install: the minified page served `list-head` and no
      `METHOD_TEXT`, three tracks were drawn on the desk from that artifact alone, and its rows read
      **Boom 1**, **Knapsack 2** and **Not recorded 1**, with the phone's own Assets screen holding the
      same three. Screenshots `cols-list.png`, `cols-card.png`, `cols-search.png`, `cols-twopass.png`,
      `cols-rel-list.png`, `cols-rel-phone-small.jpg`; notes in `build/verify/web-columns.txt` (including
      what was **not** proven).
- [x] **The track that is picked out glows. v0.6.36** — asked for in one line: "when I click (select) an
      asset, make the asset track 'glow' on the map". A click in the list (or on the track itself) now puts
      a soft halo of the asset's own colour around it, under its own line, and closing the card takes it
      away. **`glow.mjs`** builds it - one halo per layer of the phone's own work, from that layer's own
      filter, width and dash pattern - and it is pure, so `glow.test.mjs` pins it under node (11 tests), the
      way `wire.mjs` and `geometry.mjs` are pinned. What the page decides is only the glow: 7 px wider than
      the line, blurred 5, at 0.55; a place gets a soft disc behind the house instead. No Kotlin changed -
      the desk is the only screen with a selection, so the halo is the page's.
      **The pixels found a real bug in it**: a dasharray is in multiples of the line's *own width*, so the
      wider halo was drawing its dots two and a half times further apart than the line's and landing in the
      gaps between them; the fenceline's glow read as a speckle. Scaling the dasharray by the width ratio
      fixed it, and the before/after counts (42→78, 40→43, 40→42 against 42→78, 40→76, 40→76) are in the
      notes. Proven on the emulator with `db/halopix.ps1` (new): the same box around a point on the track,
      in a pair of runs that differ only by the selection - the glow adds 30-55 warm pixels to a 15x15 box
      on a track, doubles the warm pixels around a house, and the band above the card is pixel-for-pixel
      identical, so nothing else on the map moved. The **published** 0.6.36 APK was then driven on its own
      over a clean install (the installed `base.apk` the same sha256 as the release's): a track drawn from
      the minified page itself, its halo measured in a pair of shots (83 → 120 and 58 → 98 warm pixels in a
      15x15 box on the track, the band above the card pixel-for-pixel identical), and `glow.mjs` served
      **200**, so R8's build carries the module and serves it as JavaScript. Screenshots
      `glow-19-estuary.png`, `glow-21-fenceline.png`, `glow-15-trough.png`, `glow-24-rel-glow.png` and their
      `-closed` pairs; notes in `build/verify/web-glow.txt` - including the race this found (below) and what
      is **not** proven.
- [x] **The white edge, as chosen. v0.6.37** — v0.6.36's glow was reported as "not very noticeable on
      selected tracks", four treatments were drawn up as a sample sheet for the operator to judge, a second
      sheet showed the chosen casing in orange and in the app's own amber, and the answer was white. So a
      line's mark is now a **white edge** under it - the page's own paper, wider than the line by 10 px,
      blurred 2, at 0.9 - rather than a soft haze in the feature's own colour. The reason it is white, and
      now pinned by a test: white is the one colour the phone's traffic light never uses, so a line goes on
      saying when it is due while the edge round it says only "this is the one in hand". A place keeps its
      soft disc of its own colour, because a white edge round a house that already has a white edge of its
      own would say nothing. The shape is still the phone's: the same layer's filter, one narrower than the
      other, and the same dash pattern scaled so the edge's dashes land where the line's do.
      Proven on the emulator by a pair of runs that differ only by the selection: a 121x81 box on the line
      holds **651 near-white pixels with the row picked out and 0 without**, its mean colour moving from
      (58,72,64) to (80,91,84), while the bands above the card are pixel-for-pixel identical and the only
      other change in the picture is the row in the list. The **published** 0.6.37 APK was then driven on its
      own over a clean install (the installed `base.apk` the same sha256 as the release's): a track drawn from
      the minified page itself, and the edge measured in the card-free strip of the map - near-white pixels
      **9,756 -> 11,975** with the track picked out, and the bands above the card pixel-for-pixel identical.
      `glow.test.mjs` is 12 tests (two new: the edge is
      white and barely softened; and it wears no due colour and not the line's own). Screenshots
      `edge-1-selected.png`, `edge-2-closed.png`, and the two sample sheets `halo-samples.png` and
      `edge-samples.png`; notes in `build/verify/web-glow.txt`.
- [x] **The desk comes back to where it was looking. v0.6.38** — a refresh, and a basemap switch on the
      phone, both build the page again, and every one of them used to open on the whole farm: an operator
      who had spent the morning on one corner of it spent it again after every basemap. The camera is now
      kept in the **browser's own store** — never the phone's, because where a particular desk is looking
      is that desk's business, two laptops on one phone are two views, and the plan's rule that the desk's
      only write is an asset is kept by never asking the phone anything about it. It is handed to the map
      as the map is built rather than moved to afterwards, written when the map stops moving (400 ms) and
      again as the page goes away, which is the write a refresh must not miss; the fit to the work's own
      box is skipped when there is a memory. What goes in the store is five numbers and nothing else — no
      token, no asset, no name — which `camera.test.mjs` and every run's own probe pin.
      Proven on the emulator on the seeded farm, in headless-Edge profiles that stand in for the
      operator's browser (one profile reopened is a refresh; two profiles are two computers): a browser
      that has never been here draws the work's own box, **pixel for pixel** the same as the first fresh
      visit; the same browser reopened draws it **pixel for pixel**; the desk moved by a drag and a wheel
      (zoom 15.246 → **17.955**, 349,437 px of change) and then rebuilt is **pixel for pixel** where it
      was left, while a second browser differs from it by 349,507 px, so it is the memory holding the desk
      and not something else; and the phone's basemap was switched to OpenStreetMap (`/api/style`
      answering `osm,sprayday-assets`, the page fetching `/tiles/osm/19/…`) and back to aerial, with the
      desk's picture **pixel for pixel** where it was left. **The rounding the pixels threw out**: the
      first version stored the camera tidied to a centimetre, and two runs that differed only by a reload
      came back to the same place and drew a *different picture* — 485 px of difference along the tracks —
      so the numbers are kept exactly as the map gave them. `camera.test.mjs` is 8 tests.
      The **published** 0.6.38 APK was driven on its own over a clean install (`versionName=0.6.38`,
      `run-as` refusing it): `camera.mjs` served **200 / 6,913 bytes** with the key in it, and the same
      pair of runs **pixel for pixel** at (175.1358018879173, -40.650429344825795), zoom 8.450644763...
      Screenshots `cam-a-fit.png`, `cam-b-again.png`, `cam-c-moved.png`, `cam-d-reopened.png`,
      `cam-e-other.png`, `cam-f-osm.png`, `cam-g-aerial-again.png`, `cam-round-*` (the rounded build's
      counter-example) and `cam-rel-*`; notes in `build/verify/web-camera.txt`, including three things
      **not** proven and the one harness note about the ANR.
- [ ] **Phase 3, what is left** — GPX drag-and-drop, and working on more than one asset at once.
      *Show-archived was dropped* (see the phase 3 section above), so the phase's own list is now this.

Tick a box and add a line under it saying **how it was proven** — the point of this section is that a
summarised task, or a brand-new one, can see exactly where the work stopped.

## Next action

**Phase 3 — one item left of it.** Snapping is in (with the drawing itself), tracing is in (v0.6.26),
side tracks reached the wire in v0.6.29 and the desk **draws them as of v0.6.31** (the phone's own record
is `side-tracks.md`). What remains is **GPX drag-and-drop** — drop a GPX file on the desk and have it
become a track's line, which is now a well-defined thing to become: a drawing of paths, junction rules and
all — and **working on more than one asset at once**. *Show-archived was dropped, not deferred*: see the
phase 3 section above for the decision and what it leaves alone.

**The first press after a page load can be wasted.** One run in five, the first traced stroke after the
desk opened put nothing on the line, and the identical gesture worked either side of that run. It is the
same window the racy fit below moves in, and the cost is a wasted run rather than a wrong line — but two
of these now, so it is worth doing with the fit. **v0.6.36 found a worse version of it**: a click on a row
while the style is still arriving leaves the desk looking at the app's own default camera — the whole of
New Zealand — because the page's own fit is skipped when something is already picked out, so the fly is
undone rather than the press being wasted. Two of that work's first pairs landed on different cameras that
way (see `build/verify/web-glow.txt`); clicking late, after the page has settled, is what makes the runs
comparable in the meantime. **v0.6.38 narrows it to browsers that have never been here**: the desk's own
remembered view (the v0.6.38 entry above) means the fit does not run at all on a desk that has been
opened before, so there is nothing left to undo the fly — the window is still open for a fresh browser,
and the fix when the fit is next touched is the same as it was: the fly and the fit in the same place
rather than two decisions.

**One small thing found while reviewing v0.6.24's own page code**, which is not worth retagging a release
for and is one line:

1. **The Draw button is enabled a moment too early.** `boot()` enables it as soon as `edit.js` has been
   imported, which is not the same as the map's style being loaded — `edit.js`'s `ready()` calls
   `map.addSource`, which throws until the style is there. The failure is mild (the first press does
   nothing, and the second works, because by then the style has arrived) and it is unlikely (the import is
   a network round trip and the style usually wins), but the button belongs in `onStyleLoaded` where its
   comment already claims it is, with a guard in `ready()` beside it.

*(The second of the two — `editor.points()` having no caller — was done in v0.6.31: the method is gone,
and the drawing leaves the map through `onFinish` alone.)*

**One wart worth fixing while in there.** The desk's first fit is racy: `fitBounds` runs when the state
document arrives, and on some loads the container has not finished settling, so the same farm opens at a
slightly different zoom — the four bearing lines of a run are not always in the same place on the screen,
which is what made reading a handle's position off one screenshot and clicking it in the next unreliable.
Nothing is wrong with the farm or the write; the *view* is what moves, and it moves before the operator has
touched anything.

**Nothing is owing from v0.6.23.** The published 0.6.23 APK was installed and driven on its own once the
tag had built (`rel-save2.txt`) — which is the gate, because a minified build is not the debug build's
claim.

