# Drawing tracks from a computer

A web interface for drawing and editing assets — tracks, roads, fencelines, places — with a mouse
and a keyboard, because drawing them by tapping a phone is the hardest part of operating Spray Day.

**This file is a plan and a handover, not a record of work done.** When the feature ships, the
decisions below move into the README as a `## Drawing from a computer` section and this file goes
away.

## Where this stands

- **Step 0 is built and shipped; the editor itself does not exist yet.** `offline/HttpServer.kt`
  holds the HTTP plumbing both servers use, `LocalTileServer` sits on it, and nothing an operator
  can see changed (`build/verify/http-split.txt` is the evidence).
- The last shipped work is **v0.6.20** (the HTTP split, step 0 below); before it v0.6.19 (map layer
  switches). The state written here was true when the file was written — **check it rather than
  trust it** (`git status`, `HEAD` against `origin/main`, `git tag --sort=-v:refname`), because a
  plan document that claims a clean tree is a plan document that can be wrong.
- The next version to tag is **patch + 1** of the newest tag: v0.6.20 → **v0.6.21**, which is
  Phase 1.
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
| **1** | The desk view: the phone serves the editor; the map, the imagery, the asset list, due colours. **Read-only.** | **v0.6.21** |
| **2** | Editing: draw, place, move vertices, rename, metadata, delete/archive — through `AssetRepository`. | v0.6.22 |
| **3** | Desk conveniences: GPX drag-and-drop, snapping, multi-select, tracing, show-archived. | v0.6.23+ |

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

### Phase 1 — the desk view (v0.6.21)

From the operator's side: Settings gains one card, **"Draw from a computer"**, with a switch, the
address (`http://192.168.1.23:8799/?k=7f3a…`), a Copy button, and one line: *"Your computer must be
on the same Wi-Fi."* On the laptop: the same aerial imagery the phone has (its own tiles, no LINZ
key in the browser, offline areas included), every asset in its due colour and its kind's line
style, places as houses, the list grouped by block, a read-only card per asset, and a locate button
that asks **the phone** where it is.

### Phase 2 — editing (v0.6.22)

`POST /api/assets`, `PUT /api/assets/<id>` (metadata and geometry), `DELETE /api/assets/<id>`, each
calling the repository, so `polylineLengthMeters`, `groupIdFor` and the transactions stay Kotlin's.
Every write carries the asset's version back; a mismatch is **409 "this changed on the phone"** with
the differing fields shown, rather than an overwrite.

Editing: click to select, drag a vertex, click a segment to insert one, ⌫ removes one, double-click
or Enter finishes, Esc cancels, Ctrl+Z undoes (a local pure stack in `edit.js`, tested without a
browser), snapping to the first vertex and to other assets, kind/shape/method/interval/swath/passes/
block/notes, and rename. The phone's own map and list update as you work, because it is one
database and Room flows already push changes.

**Delete rule.** The browser is told what is attached and shows it: *"Delete Estuary road? 3 sprays
go with it; its recording stays."* If the phone reports sprays or recordings attached, the web may
only **archive** (`active = false`) and the real delete stays on the phone; if nothing is attached, a
mis-drawn track may be deleted from the desk.

## Files

**New — phone side**

| File | What it does |
| --- | --- |
| `offline/HttpServer.kt` | Socket, accept loop, request-line and header parse, response writing, route table. No app knowledge. **Landed in v0.6.20.** |
| `web/WebEditorLink.kt` | LAN addresses from `NetworkInterface` (all candidates; no permission needed), the per-session token, the URL. Address picking is pure and unit-tested. |
| `web/WebEditorServer.kt` | The routes, bound to the Wi-Fi address, alive only while the switch is on. Fixed port 8799, next free port if taken. |
| `web/WebEditorService.kt` | Foreground service, type `dataSync`, notification carrying the URL. `tracking/TrackingService.kt` is the pattern. |
| `web/WebEditorJson.kt` | The state document. **Reuses `AssetRecord` / `LinePointRecord` / `GroupRecord`** from `domain/backup` — the vocabulary that is already versioned and tested — wrapped with the view fields rather than growing a second asset shape. |
| `map/WebStyleJson.kt` | The style the page loads: the basemap raster source with the LAN tile URL, **plus the four asset layers using the very same ids as `AssetLayerIds`**, dashes from `AssetLineStyles`, colours from `AssetColors`, house pictures named as `PlaceIcons` names them, and a geojson source pointing at `/api/assets.geojson`. |
| `app/src/main/assets/web/` | `index.html`, `app.js`, `edit.js` (phase 2), `style.css`, `vendor/maplibre-gl.js`, `vendor/maplibre-gl.css`, `vendor/LICENSE-mapLibre`. Plain ES modules: the file you edit is the file that runs. |

**Changed**

| File | Change |
| --- | --- |
| `offline/LocalTileServer.kt` | Uses the extracted `HttpServer`; its routes and its loopback-only guarantee are unchanged, word for word. |
| `data/SettingsRepository.kt`, `ui/screens/SettingsScreen.kt` | The "Draw from a computer" card: the switch, the address, Copy. |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_DATA_SYNC` and the service. `INTERNET` is already there. No other permission. |
| `README.md` | A `## Drawing from a computer` section, and the vendored licence note. |

## Endpoints

- `GET /` + the static page from `app/src/main/assets/web/` — so the editor ships inside the APK,
  version-locked with the app, and needs no internet at all.
- `GET /api/state` — the active assets (kind, shape, method, block, interval, swath, passes,
  `lengthM`, `lastSprayedAtEpochMs`, **`dueStatus` and `dueAtEpochMs` from `DueCalculator`**, and
  `[[lng,lat],…]`), the groups, the products (read-only), the work's bbox, and the phone's last fix
  from `tracking/DevicePosition.kt` — better than the browser's own geolocation, which a browser
  blocks on an insecure origin anyway.
- `GET /api/assets.geojson` — what the style's source reads.
- `GET /api/style` — the style, including `"placeIcons"`: which pictures the page must draw and
  register, by the names the style asks for. The phone decides the picture; the page paints it (a
  canvas port of `map/HouseMarker.kt`'s house).
- `GET /tiles/<source>/{z}/{x}/{y}` — the same handler and the same stores the app's own map uses.
- Phase 2 only: `POST /api/assets`, `PUT /api/assets/<id>`, `DELETE /api/assets/<id>`.
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

## How to verify (phase 1)

1. Install the debug build over a seeded database (`build/verify/db/seed*.py`, pushed with
   `adb push` + `run-as nz.mckenzie.sprayday`); flip the switch on.
2. `adb forward tcp:8799 tcp:8799` puts the phone's endpoint on this machine, so the real page can
   be driven against real data without a Wi-Fi dance. Screenshot the page and assert the same
   assets, the same due colours and the same geometry bbox the app's own map shows.
3. No token → 403; wrong token → 403; right token → the page.
4. Switch off → the port is closed, and `netstat` shows the loopback listener and the LAN listener
   as two separate sockets, proving the loopback promise is intact.
5. Screen off for ten minutes → the page still loads (foreground service, Doze).
6. Re-run the step-0 proof: the app's own map still draws its tiles.

## Progress

- [x] **Step 0** — `HttpServer` split out of `LocalTileServer`; `LocalTileServerTest` green and
      untouched; the app's own map still drawing imagery (screenshot). **v0.6.20.**
      Proven: 11 untouched wire tests plus 8 new `HttpServerTest` tests green, lint 0 errors with
      nothing naming the two files, the pre-change release screenshot and the new build's identical
      pixel for pixel over the map, tiles fetched and stored on the way through the refactored
      plumbing, and the **published** v0.6.20 APK installed and pixel-identical to the debug build -
      a minified build is not the debug build's claim. Notes in `build/verify/http-split.txt`.
- [ ] **Phase 1** — the desk view: the Wi-Fi server, the token, `/api/state`, `/api/style`, the page,
      the Settings card. Verified by the list above. Tagged **v0.6.21**.
- [ ] **Phase 2** — editing through `AssetRepository`, the 409 version check, the archive-only delete
      rule. Tagged **v0.6.22**.
- [ ] **Phase 3** — desk conveniences: GPX drop, snapping, multi-select, tracing, show-archived.

Tick a box and add a line under it saying **how it was proven** — the point of this section is that a
summarised task, or a brand-new one, can see exactly where the work stopped.

## Next action

**Phase 1, in this order**: `web/WebEditorLink.kt` and its test first — the LAN addresses from
`NetworkInterface` and the per-session token decide what the Settings switch will say, and the
address picking is pure, so it is worth pinning before any socket exists. Then `web/WebEditorServer.kt`
as a second `HttpServer` on the Wi-Fi address, with the token gate in front of **everything**
including `/`; then `/api/state`, `/api/style` and the tile route sharing the app's own stores; then
the page under `app/src/main/assets/web/` with MapLibre GL JS vendored; then the Settings card and
the foreground service. Proof is the list under *How to verify (phase 1)* — 403 without a token,
403 with the wrong one, the page's assets and due colours matching the app's own map, and `netstat`
showing the loopback listener and the LAN listener as two separate sockets. Tag it **v0.6.21** and
tick the box above with how it was proven.

