/*
 * Where the desk was looking.
 *
 * Pure, and separate from `app.js` for the same reason `glow.mjs` and `wire.mjs` are: node can run it
 * without a browser (`app/src/test/js/camera.test.mjs`, which CI runs), so what the memory will keep and
 * what it will refuse are pinned by a test rather than reviewed by eye.
 *
 * **The page's own memory of its own view.** Switching the basemap on the phone, or pressing refresh,
 * throws the page away and builds it again - the page never reloads itself, so both arrive as the same
 * thing - and until this existed every one of them opened on the whole farm again. An operator who has
 * spent the morning on one corner of it spends it again after every basemap switch. So the camera is kept
 * and put back before the first frame.
 *
 * **The browser's own store, not the phone.** Where a particular desk is looking is the desk's own
 * business: two laptops open on the same phone are two views rather than one, and the phone's own map has
 * a camera of its own that a page has no business moving. The plan's rule is that the desk's only write
 * is an asset, and this keeps it - the memory never leaves the machine the operator is sitting at.
 *
 * **Only the camera is kept.** The store outlives the page and is readable by anything else served from
 * the same address, so what goes in it is five numbers and nothing else: no token, no asset, no name.
 */

/**
 * What the desk calls this in the browser's store.
 *
 * A store belongs to one *address* - scheme, host and port - so this names the view one desk has of one
 * phone's work, and a second phone is a second address with a memory of its own.
 */
export const CAMERA_KEY = 'sprayday-desk-camera';

/**
 * How far out the memory will be believed.
 *
 * The map is flat, so past the Mercator limit there is nothing to draw and a latitude beyond it is not a
 * place; MapLibre's own range for a zoom is what the numbers below say; and a tilt past sixty degrees is
 * past what the map itself will do. A stored value outside all of this is treated as no memory at all,
 * which is the one safe answer: a desk that opens on the whole farm is a small nuisance, and a desk that
 * opens on blank ocean looks broken.
 */
const MAX_LAT = 85.05113;
const MAX_LNG = 180;
const MIN_ZOOM = 0;
const MAX_ZOOM = 24;
const MAX_PITCH = 60;
const MAX_BEARING = 180;

/**
 * How finely the camera is kept: not at all, and that is the decision worth writing down.
 *
 * The first version of this file rounded the camera to a centimetre of ground and a thousandth of a zoom
 * step, on the argument that a position is not a measurement to sixteen digits. The runs that verified the
 * memory showed what that costs: an operator's desk came back to the same *place* and drew a different
 * *picture* - a few hundred pixels of difference, along the tracks, because a line put a hundredth of a
 * pixel from where it was lands on different pixels. Nearly the same view is not what was asked for, and
 * it makes "the page came back to where it was" a claim no picture can settle. So the numbers are kept as
 * the map gave them, and `JSON` writes a double round-trippably, so what is read back is what was written.
 */

function inRange(value, min, max) {
  // `Number.isFinite` refuses NaN and the infinities, which a number out of a JSON store could otherwise
  // be: `{"zoom":null}` parses to null, and `null >= 0` is *true*, which is exactly the kind of value that
  // would take the map somewhere silly.
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max;
}

/** Whether a value is a camera this file would open the desk on. */
export function isCamera(value) {
  if (!value || typeof value !== 'object') return false;
  return inRange(value.lng, -MAX_LNG, MAX_LNG)
    && inRange(value.lat, -MAX_LAT, MAX_LAT)
    && inRange(value.zoom, MIN_ZOOM, MAX_ZOOM)
    && inRange(value.bearing, -MAX_BEARING, MAX_BEARING)
    && inRange(value.pitch, 0, MAX_PITCH);
}

/**
 * The camera a map is looking through now, in the terms this file keeps it in - the numbers it gave, not
 * numbers this file tidied (see above: a tidied camera is a different picture along the tracks).
 *
 * Only MapLibre's own getters are used, so a test can hand this a stub rather than a map.
 */
export function cameraOf(map) {
  const centre = map.getCenter();
  return {
    lng: centre.lng,
    lat: centre.lat,
    zoom: map.getZoom(),
    bearing: map.getBearing(),
    pitch: map.getPitch()
  };
}

/**
 * A camera as the options MapLibre's own `Map` takes them.
 *
 * Handed to the map at the moment it is built rather than moved to afterwards: a map that opens on the
 * style's own centre and then jumps is two views, and the first of them asks the phone for imagery nobody
 * is going to look at. The order below is MapLibre's - `[lng, lat]`, longitude first - and this is the one
 * line in the file where getting it the other way round would put the desk in the sea.
 */
export function openingCamera(camera) {
  return {
    center: [camera.lng, camera.lat],
    zoom: camera.zoom,
    bearing: camera.bearing,
    pitch: camera.pitch
  };
}

/**
 * Keeps the camera for the next time this page is built.
 *
 * A store that refuses to be written to - storage switched off, a private window, a full quota - is not a
 * failure worth telling anybody about: a desk that cannot remember where it was is still a desk. Nor is a
 * camera that could not be read back, so nothing `recall` would refuse is ever written.
 */
export function remember(storage, camera) {
  if (!storage || !isCamera(camera)) return;
  try {
    storage.setItem(CAMERA_KEY, JSON.stringify({
      lng: camera.lng,
      lat: camera.lat,
      zoom: camera.zoom,
      bearing: camera.bearing,
      pitch: camera.pitch
    }));
  } catch (error) {
    // Nothing to do about it and nothing to say: the operator loses a convenience, not their work.
  }
}

/**
 * The camera the last visit left, or null when there is none to be had - which is how a page opens on the
 * whole farm the first time, and how it opens on the whole farm again if the store is unreadable, was
 * written by something else, or holds a camera from a page whose shape was different.
 *
 * What comes out is built from the five numbers rather than handed over as it was stored, so anything else
 * that found its way into the store cannot travel into the map's options.
 */
export function recall(storage) {
  if (!storage) return null;
  let stored = null;
  try {
    stored = storage.getItem(CAMERA_KEY);
  } catch (error) {
    return null;
  }
  if (!stored) return null;

  let value = null;
  try {
    value = JSON.parse(stored);
  } catch (error) {
    return null;
  }
  if (!isCamera(value)) return null;

  return {
    lng: value.lng,
    lat: value.lat,
    zoom: value.zoom,
    bearing: value.bearing,
    pitch: value.pitch
  };
}
