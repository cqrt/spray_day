/*
 * Where the desk was looking, and whether it can be believed.
 *
 * `camera.mjs` is pure, so node runs the very file the browser does: what the memory keeps, what it
 * refuses, and - the part worth pinning - that what comes back out of it is a camera and nothing else. The
 * store it writes to is the one place on that machine which outlives the page, so the token must not be in
 * it and neither must anything else the phone owns.
 *
 * The other half of what is being held is the safe direction of failure. A desk that cannot remember is a
 * desk that opens on the whole farm, which is what the page did before any of this existed; a desk that
 * believes a corrupted store opens on blank ocean with no work on it, which looks like a broken phone. So
 * everything doubtful reads as no memory at all, and that is most of what is tested here.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  CAMERA_KEY, cameraOf, isCamera, openingCamera, recall, remember
} from '../../main/assets/web/camera.mjs';

/** A browser's own store, as far as this file is concerned: two methods, and what is left in it. */
function store(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key) => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => values.set(key, String(value)),
    left: () => Object.fromEntries(values)
  };
}

/** A map, as far as this file is concerned: the getters MapLibre answers those four questions with. */
function mapLookingAt({ lng, lat, zoom = 14.5, bearing = 0, pitch = 0 }) {
  return {
    getCenter: () => ({ lng, lat }),
    getZoom: () => zoom,
    getBearing: () => bearing,
    getPitch: () => pitch
  };
}

test('a browser that has never been here has no camera, so the desk opens on the whole farm', () => {
  assert.equal(recall(store()), null, 'an empty store is no memory');
  assert.equal(recall(null), null, 'and a browser that hands over no store at all is no memory either');
});

test('the camera is kept exactly as the map gave it, so the desk comes back to the same view', () => {
  // The numbers a real fit produced: an awkward centre and the zoom it worked out.
  const looking = cameraOf(mapLookingAt({ lng: 173.53330000000001, lat: -35.54340000000002, zoom: 15.2459876 }));

  assert.deepEqual(looking, {
    lng: 173.53330000000001, lat: -35.54340000000002, zoom: 15.2459876, bearing: 0, pitch: 0
  });

  // Not "the same place to the nearest centimetre": the *same numbers*. A camera tidied even that much
  // draws the tracks on different pixels - see the runs in `build/verify/web-camera.txt`.
  const storage = store();
  remember(storage, looking);
  assert.deepEqual(recall(storage), looking, 'write, read back and hand to the map are one value');
});

test('a camera kept is a camera read back, handed to the map longitude first', () => {
  const storage = store();
  const looking = cameraOf(mapLookingAt({ lng: 174.5869385, lat: -40.9053496, zoom: 15.877 }));

  remember(storage, looking);

  assert.deepEqual(recall(storage), looking, 'the same place, to the last digit kept');
  assert.deepEqual(
    openingCamera(looking).center,
    [174.5869385, -40.9053496],
    'MapLibre takes [lng, lat] - the other way round is a desk in the sea'
  );
  assert.equal(openingCamera(looking).zoom, 15.877, 'and the zoom it was left at');
});

test('a rotation and a tilt are kept with it, because the desk is left as it is left', () => {
  const looking = cameraOf(mapLookingAt({ lng: 174.5, lat: -40.9, bearing: 27.34, pitch: 41.2 }));

  assert.equal(looking.bearing, 27.34, 'the rotation, as the map gave it');
  assert.equal(looking.pitch, 41.2, 'the tilt, the same');
  const storage = store();
  remember(storage, looking);
  assert.deepEqual(recall(storage), looking, 'read back with the map turned the way it was left');
});

test('rubbish in the store is no memory at all, never a camera', () => {
  const notCameras = {
    'not JSON at all': 'the estuary',
    'a number': '42',
    'nothing': 'null',
    'an empty object': '{}',
    'a camera missing its zoom': '{"lng":174.5,"lat":-40.9,"bearing":0,"pitch":0}',
    'a camera with a null zoom': '{"lng":174.5,"lat":-40.9,"zoom":null,"bearing":0,"pitch":0}',
    'a camera written as strings': '{"lng":"174.5","lat":"-40.9","zoom":"12","bearing":0,"pitch":0}',
    'a longitude off the world': '{"lng":400,"lat":-40.9,"zoom":12,"bearing":0,"pitch":0}',
    'a latitude past the flat map itself': '{"lng":174.5,"lat":89,"zoom":12,"bearing":0,"pitch":0}',
    'a zoom past what the map draws': '{"lng":174.5,"lat":-40.9,"zoom":40,"bearing":0,"pitch":0}',
    'a tilt past upright': '{"lng":174.5,"lat":-40.9,"zoom":12,"bearing":0,"pitch":90}',
    'a rotation past a full turn': '{"lng":174.5,"lat":-40.9,"zoom":12,"bearing":400,"pitch":0}'
  };

  for (const [what, stored] of Object.entries(notCameras)) {
    assert.equal(recall(store({ [CAMERA_KEY]: stored })), null, `${what} is no memory`);
  }
});

test('nothing but a camera comes out of the store, whatever else is in there', () => {
  const storage = store({
    [CAMERA_KEY]: JSON.stringify({
      lng: 174.5869385, lat: -40.9053496, zoom: 15.877, bearing: 0, pitch: 0,
      k: 'a token somebody left lying about',
      asset: 7
    })
  });

  assert.deepEqual(
    recall(storage),
    { lng: 174.5869385, lat: -40.9053496, zoom: 15.877, bearing: 0, pitch: 0 },
    'the five numbers, and none of the rest of it'
  );
});

test('a store that will not be written to, or read from, is a desk with no memory rather than a failure', () => {
  const refused = {
    getItem: () => { throw new Error('storage is switched off'); },
    setItem: () => { throw new Error('storage is switched off'); }
  };
  const camera = cameraOf(mapLookingAt({ lng: 174.5, lat: -40.9 }));

  assert.equal(recall(refused), null, 'a store that refuses to be read is no memory');
  assert.doesNotThrow(() => remember(refused, camera), 'and one that refuses to be written is not an error');
});

test('a camera the desk could not open on is never kept, so the store only holds what can be used', () => {
  const storage = store();
  const camera = cameraOf(mapLookingAt({ lng: 174.5, lat: -40.9 }));

  remember(storage, { ...camera, zoom: NaN });
  assert.deepEqual(storage.left(), {}, 'a broken camera leaves the store as it was');
  assert.equal(isCamera({ ...camera, lng: null }), false, 'and a null longitude is a broken camera');
  assert.equal(isCamera(camera), true, 'while a real one is');

  remember(storage, camera);
  assert.deepEqual(recall(storage), camera, 'and a real one is kept');
});
