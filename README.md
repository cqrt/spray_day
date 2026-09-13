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
- **Tracks** as first-class objects: import GPX, draw them by tapping the map, or
  record them by driving the line.
- **Spray records**: pick a track, enter the products and the **mL of each**,
  save. The track turns green and its history starts. Amounts are remembered per
  track, so the next pass is a confirmation rather than a retype.
- **GPS recording** via a `location`-type foreground service, with every fix
  written to the database as it arrives.
- **GPX export** of any track, shareable to QGIS/Google Earth/forestry tools.

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
registration; note that standard keys **expire every 90 days** — the app also
accepts a key entered at runtime, so an expired key never bricks an install).

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

## Data attribution

Basemap imagery is licensed **CC BY 4.0** and provided by LINZ. Every map screen
shows `LINZ CC BY 4.0 © Imagery Basemap contributors`, linking to LINZ's
[attribution page](https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data).
