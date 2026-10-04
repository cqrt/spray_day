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
- **Near me** adds an envelope around the phone's newest fix, 25 km each way. A degree of longitude
  is shorter this far south, so the box is wider than it is tall; it is a bounding box, not a
  distance test.
- Either alone is enough, and both together narrow each other.

The service answers a **page** (300 tracks). A page that comes back full says so, rather than letting
a capped search look like a complete one.

## What it reads, and what it writes

`DocTracksJson` reads the answer with `GeoJsonParser` — the same reader a GeoJSON file gets — and
turns each feature's paths into a track with `TrackInterchange.reading`, so a DOC track is a line
with side tracks exactly as a file's is. It keeps what the browser needs beyond the name: `OBJECTID`
(its key) and `SubObjectType` (its kind).

Importing is the same `AssetRepository.createAsset` every drawn or imported track goes through, one
asset per ticked track, named as DOC names it. So the moment it lands a DOC track is a track like any
other: it can be edited, sprayed, blocked, exported.

## Files

| File | What it is |
| --- | --- |
| `domain/doc/DocTrackQuery.kt` | The question and the URL that asks it: the `where` clause, the near-me envelope, `f=geojson&outSR=4326`. Pure, so the URL is held by a JVM test. |
| `domain/doc/DocTrack.kt` | One track the service holds: its key, name, kind, and the reading of its paths; its own metres and points. |
| `domain/doc/DocTracksJson.kt` | The service's answer read as tracks, through `GeoJsonParser` and `TrackInterchange`. |
| `doc/ArcGisDocTracks.kt` | The live service, asked over HTTP. An interface (`DocTracksSource`) so the screen is tested against a fake. |
| `viewmodel/DocTracksViewModel.kt` | The search, the ticks, the import, and the phone's own newest fix for "Near me". |
| `ui/screens/DocTracksScreen.kt` | The screen: a name field, Near me, Search, a tickable list, Import. |
| `test/.../domain/doc/` | The URL and the answer, held by JVM tests. |

## Notes and limits

- **The dataset is deprecated.** It still answers, and the app will keep working, but a replacement is
  coming; see the one-line change above.
- **Attribution.** DOC's data is CC-BY 4.0 and Crown copyright. The tracks are DOC's, not the app's;
  any handover or report derived from them should carry DOC's acknowledgement.
- **Offline** there is no search: the answer is a sentence naming the fault, not a crash, and the
  app's own files still import as before.
