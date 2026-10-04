# DOC tracks

A screen that searches the Department of Conservation's published track network and imports the
tracks the operator ticks, as ordinary assets. It is how a track that DOC already holds gets into
this app without anyone exporting a file first.

## The source

DOC publishes its tracks as a hosted **ArcGIS Feature Service**, not a file store:

- item `5cba3b0a2e1041c9ad02ec694f3f3d37`, **"DOC Tracks — Deprecated Dataset"**, public, CC-BY 4.0,
  Crown copyright;
- one polyline layer, **3,255 tracks**, native **NZTM2000 (EPSG:2193)**, refreshed nightly upstream;
- `https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services/DOC_Tracks_EAM/FeatureServer/0`

**DOC has deprecated this dataset and says a replacement is coming.** The service is therefore named
in exactly one place — `DocTracksUrl.SERVICE_URL` — so the day the replacement has a URL, one line
changes and nothing else does.

## How it searches

Searching happens at DOC; the phone never downloads the whole network. A search is an ArcGIS REST
query (`DocTracksUrl`), and asking for `f=geojson` with `outSR=4326` has ArcGIS reproject from NZTM
to WGS84 for us — so there is no projection arithmetic in the app, and the answer is the GeoJSON the
importer already reads.

- **A name** becomes `UPPER(TechObjectName) LIKE UPPER('%…%')`, case-insensitively. A quote in the
  name is doubled, so it searches for a name rather than ending the clause.
- **Near me** adds an envelope around the phone's newest fix, **25 km by default and set by the
  operator** (the phone screen's km field, the desk's "Within … km"). A degree of longitude is shorter
  this far south, so the box is wider than it is tall. The box is only the service's quick filter,
  though: its corners reach about 1.4× the radius, and any long track that clips it passes right
  through, so what comes back is then **kept only where the nearest point is truly within the radius**
  — the circle the operator asked for. A near-me search with no fix yet is refused rather than done
  without the place, which is how tracks 80 km away used to turn up under a 50 km radius. Each row
  says how far away it is, from the phone's own fix.
- Either alone is enough, and both together narrow each other.

The service answers a **page** (300 tracks). A page that comes back full says so, rather than letting
a capped search look like a complete one.

## Filtering and ordering

A search's results are narrowed and reordered **without asking DOC again** — the page already holds
what it found:

- **Kinds** are offered as chips taken from what the search actually returned — `Tramping Track`,
  `Short Walk`, `Walking Track`, `Great Walk`, `Easy Tramping Track`, `Route` — and any of them can be
  picked, together or alone. Picking none is every kind. The list is built from the results rather than
  from DOC's taxonomy kept here, so a filter can only ever offer a kind that is on the page.
- **Nearest to phone** orders by the distance from the phone's own fix to the **nearest vertex** of
  each track — a long line passing beside you is near you, where its middle would not be — and it
  needs no second request: the phone sends each track's distance, so the desk's order is the phone's.
  With no fix it falls back to **Name**, A to Z.

It is all the phone's arithmetic and the phone's answers, so both browsers order the same list the
same way, and the nearest order re-sorts as the phone moves.

## Already-imported tracks

A track that is already on the phone is shown in the list but **cannot be ticked or imported again**,
on either surface. The list says *already imported* beside it, the box is off, and the phone refuses
it at the door that writes as well as in the list — so a second search cannot double the work.

It is known by an **exact source reference**, not a name: importing writes `doc:<OBJECTID>` into
`AssetEntity.sourceRef`, and a search reads every such reference once and marks each candidate against
it. So a renamed asset is still recognised, and deleting the asset makes the track importable again.
The reference travels in a backup too, so a restore does not forget which tracks are already here.

`sourceRef` arrived with schema **v8** (`MIGRATION_7_8`, an `ALTER TABLE assets ADD COLUMN sourceRef
TEXT`). Existing rows are left with none — nothing in a v7 database came from a service, and inventing
a source would claim a provenance nobody gave — and the migration is proved against a populated v7
database like every migration before it.

## What it reads, and what it writes

`DocTracksJson` reads the answer with `GeoJsonParser` — the same reader a GeoJSON file gets — and
turns each feature's paths into a track with `TrackInterchange.reading`, so a DOC track is a line
with side tracks exactly as a file's is. It keeps what the browser needs beyond the name: `OBJECTID`
(its key) and `SubObjectType` (its kind); `DocTrack.sourceRef` is `doc:<OBJECTID>`.

Importing is the same `AssetRepository.createAsset` every drawn or imported track goes through, one
asset per ticked track, named as DOC names it. So the moment it lands a DOC track is a track like any
other: it can be edited, sprayed, blocked, exported.

## On the computer

The editor served to a laptop has the same browser. It is the same search and the same import, and
**the phone does the asking there too**: the page calls the phone (`GET /api/doc`) and the phone
calls DOC, so the laptop never reaches the service itself and the service is still named in one place.

There are two answers to "where", and they are one answer:

- **Near the phone** is the **phone's** own fix from the state document, not the browser's location —
  the work and the service are the phone's, and a desk has no business pretending to be the tractor.
- **Only what's on the map** is the desk's own view, turned into a box (`map.getBounds()`) and sent as
  `bounds=minLat,minLng,maxLat,maxLng`. It is a different question — *the paddock I am looking at* —
  and it is the desk's to answer, because the desk is where the map is.
- Turning one on turns the other off, so a search never carries both and the operator is never unsure
  which it used. A view wins if both are somehow given.
- **Within … km** sets the radius Near the phone searches; 25 is the default. It goes with the place,
  not with the view, which has no radius to send.

A track the phone already has is marked **already imported** on the desk too: the row is drawn, its
box is off and disabled, and it is left out of the request even if a page sends it.

- `GET /api/doc?name=…&near=lat,lng&radiusKm=…&bounds=minLat,minLng,maxLat,maxLng` answers with the
  tracks, which of them are already here, and the phone's own word when there is one to say.
- `POST /api/doc/import` carries the ticked tracks' keys, names and the geometry the phone already
  showed, and makes them with the same `createAsset` every drawn track goes through — so each records
  the `doc:…` reference that keeps it from being imported twice. One request for the lot.
- `app/src/main/assets/web/doc.mjs` is the desk's half — the query and the import body — pure, and
  tested under node like `wire.mjs`.

## Offline

The browser is useless with no reception unless the tracks are already on the phone. The **Offline
areas** screen has two tabs — **Imagery** and **DOC tracks** — because the two are **independent
caches**: imagery makes the map work offline, DOC's tracks make this browser work offline, and neither
has anything to do with the other. They are counted, cleared and filled separately.

They choose an area the same way: **Choose an area on the map** opens the picker, two opposite
corners are tapped, and the download runs. There is no area offered by location any more — the
operator says where, on the map, which is the one thing a location guess was standing in for.

- **Download DOC tracks** pulls every track the service has inside the box, a page at a time
  (`DocTrackDownloader`; 2,000 a request, capped at 4,000), and stores them keyed by the service's own
  `OBJECTID` — so two overlapping areas end with one copy, not two. It is independent of the imagery:
  no LINZ key is needed, and an operator who only wants tracks pays for nothing else.
- The store is a **cache**, not the record of the farm: it is not backed up, and *Clear downloaded DOC
  tracks* costs nothing but the download.
- With no service, both browsers fall back to the cache: the search is filtered by name — the download
  is the place filter, because the cache only holds the areas asked for — and the list says
  *Could not reach DOC. Showing N downloaded tracks.* Importing works exactly as it does online,
  because it always did: the geometry is already in hand.

`DocTrackCache` is the interface the browsers depend on; `OfflineDocTrackStore` is the Room-backed
one, beside the offline imagery. `DocPathsJson` is how a cached track's geometry travels — `[lat, lng]`
pairs in one JSON column — because a cache row is written once and read whole and nothing joins to it.

The table arrived with schema **v9** (`MIGRATION_8_9`), proved against a populated v8 database like
every migration before it.

## Files

| File | What it is |
| --- | --- |
| `domain/doc/DocTrackQuery.kt` | The question and the URL that asks it: the `where` clause, the near-me envelope, `f=geojson&outSR=4326`. Pure, so the URL is held by a JVM test. |
| `domain/doc/DocTrack.kt` | One track the service holds: its key, name, kind, and the reading of its paths; its own metres and points. |
| `domain/doc/DocTracksJson.kt` | The service's answer read as tracks, through `GeoJsonParser` and `TrackInterchange`. |
| `doc/ArcGisDocTracks.kt` | The live service, asked over HTTP. An interface (`DocTracksSource`) so the screen is tested against a fake. |
| `viewmodel/DocTracksViewModel.kt` | The search, the ticks, the import, and the phone's own newest fix for "Near me". |
| `ui/screens/DocTracksScreen.kt` | The screen: a name field, Near me with a km field, Search, a tickable list, Import. |
| `data/db/AssetEntity.kt`, `Migrations.kt` | `sourceRef`, the exact key a service track was imported under, and `MIGRATION_7_8` that adds it (schema v8). |
| `data/db/OfflineDocTrackEntity.kt`, `OfflineDocTrackDao.kt`, `MIGRATION_8_9` | The offline DOC cache table and its v9 migration. |
| `domain/doc/DocTrackCache.kt` | The cache the browsers depend on, and the name filter used when DOC cannot be reached. |
| `domain/doc/DocPathsJson.kt` | A cached track's geometry as `[lat, lng]` pairs in one JSON column. |
| `doc/DocTrackDownloader.kt` | Reading a whole area's tracks a page at a time, keeping what arrived when it stops short. |
| `offline/OfflineDocTrackStore.kt` | The Room-backed cache beside the offline imagery, and the download that fills it. |
| `web/WebEditorDocuments.kt` | The desk's two asks: `docSearch` runs the same `DocTracksSource` the phone screen does and marks what is already here, and `docImport` makes the ticked tracks with `createAsset`. |
| `web/WebEditorServer.kt` | `GET /api/doc` and `POST /api/doc/import`, beside the other routes and behind the same token gate. |
| `web/WebEditorJson.kt` | The documents those two answer with, and the two data shapes `doc.mjs` sends and reads. |
| `app/src/main/assets/web/doc.mjs` | The desk's half: the query a search is and the body an import is. Pure, tested under node. |
| `test/.../domain/doc/` | The URL and the answer, held by JVM tests. |

## Notes and limits

- **The dataset is deprecated.** It still answers, and the app will keep working, but a replacement is
  coming; see the one-line change above.
- **Attribution.** DOC's data is CC-BY 4.0 and Crown copyright. The tracks are DOC's, not the app's;
  any handover or report derived from them should carry DOC's acknowledgement.
- **Offline** there is no search: the answer is a sentence naming the fault, not a crash, and the
  app's own files still import as before.
