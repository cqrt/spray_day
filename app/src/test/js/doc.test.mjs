/*
 * The desk's half of the DOC Tracks browser, under node.
 *
 * `doc.mjs` is pure, so node runs the very file the browser does: what a search asks the phone for,
 * what an import carries back, and how the answer is worded. What is *not* here is the search itself
 * or the import: those are the phone's, reached through `GET /api/doc` and `POST /api/doc/import`,
 * and they are tested there.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { docImportBody, docKindsOf, docSearchNote, docSearchPath, docShowing } from '../../main/assets/web/doc.mjs';

test('a search with neither a name nor a place is the plain path', () => {
  assert.equal(docSearchPath('', null), '/api/doc');
  assert.equal(docSearchPath('   ', null), '/api/doc');
});

test('a name goes in the query, trimmed', () => {
  assert.equal(docSearchPath('Glory', null), '/api/doc?name=Glory');
  assert.equal(docSearchPath('  Ocean Beach  ', null), '/api/doc?name=Ocean+Beach');
});

test('Near adds the phone\'s own place, to the south and east as a lat and a lng', () => {
  const path = docSearchPath('', { lat: -46.6, lng: 168.35 });

  assert.match(path, /near=-46\.6%2C168\.35/);
});

test('a map view is a box, south-west then north-east', () => {
  const path = docSearchPath('', null, { minLat: -46.7, minLng: 168.1, maxLat: -46.5, maxLng: 168.6 });

  assert.match(path, /bounds=-46\.7%2C168\.1%2C-46\.5%2C168\.6/);
  assert.doesNotMatch(path, /near=/, 'the view is the answer to where, so no place is sent with it');
});

test('when both a place and a view are given, the view wins', () => {
  const path = docSearchPath('', { lat: -46.6, lng: 168.35 }, { minLat: -46.7, minLng: 168.1, maxLat: -46.5, maxLng: 168.6 });

  assert.match(path, /bounds=/);
  assert.doesNotMatch(path, /near=/);
});

test('a radius goes with the place it belongs to, and nowhere else', () => {
  assert.match(docSearchPath('', { lat: -46.6, lng: 168.35 }, null, 50), /radiusKm=50/);
  assert.doesNotMatch(
    docSearchPath('', { lat: -46.6, lng: 168.35 }, null, 'not a number'),
    /radiusKm=/,
    'nonsense is left out and the phone falls back to its own default'
  );
  assert.doesNotMatch(
    docSearchPath('', null, { minLat: -46.7, minLng: 168.1, maxLat: -46.5, maxLng: 168.6 }, 50),
    /radiusKm=/,
    'a map view has no radius to send'
  );
});

test('and both together narrow each other', () => {
  const path = docSearchPath('Foveaux', { lat: -46.6, lng: 168.35 });

  assert.match(path, /name=Foveaux/);
  assert.match(path, /near=-46\.6%2C168\.35/);
});

test('an import carries each ticked track\'s key, name and the geometry it was shown', () => {
  const sky = [{ lat: -46.6, lng: 168.3 }, { lat: -46.61, lng: 168.31 }];

  assert.deepEqual(
    docImportBody([{ id: 1, name: 'Glory Tk', paths: [sky] }]),
    { tracks: [{ id: 1, name: 'Glory Tk', paths: [sky] }] }
  );
});

test('the note is the phone\'s word when it has one, and otherwise a count', () => {
  assert.equal(docSearchNote({ message: 'No DOC tracks matched.', tracks: [] }), 'No DOC tracks matched.');
  assert.equal(docSearchNote({ tracks: [] }), 'No DOC tracks matched.');
  assert.equal(docSearchNote({ tracks: [{}] }), '1 track');
  assert.equal(docSearchNote({ tracks: [{}, {}, {}] }), '3 tracks');
});

test('a page that was only the first of more says so', () => {
  const tracks = Array.from({ length: 300 }, () => ({}));

  assert.match(docSearchNote({ tracks, capped: true }), /first 300 matches/);
  assert.equal(docSearchNote({ tracks, capped: false }), '300 tracks');
});

test('nothing to say is said as nothing, rather than a crash', () => {
  assert.equal(docSearchNote(null), '');
  assert.equal(docSearchNote(undefined), '');
});

test('the kinds present are each once, sorted, and blanks are left out', () => {
  const tracks = [
    { kind: 'Tramping Track' },
    { kind: 'Short Walk' },
    { kind: 'Tramping Track' },
    { kind: null },
    { kind: '  ' }
  ];

  assert.deepEqual(docKindsOf(tracks), ['Short Walk', 'Tramping Track']);
  assert.deepEqual(docKindsOf(undefined), []);
});

test('nearest orders by the distance the phone sent', () => {
  const near = { name: 'Near', kind: 'Walking Track', distanceM: 10 };
  const far = { name: 'Far', kind: 'Walking Track', distanceM: 9000 };

  assert.deepEqual(docShowing([far, near], [], 'nearest'), [near, far]);
});

test('a track with no distance sorts last, and a tie falls back to the name', () => {
  const known = { name: 'B', kind: 'Walking Track', distanceM: 5 };
  const unknown = { name: 'A', kind: 'Walking Track', distanceM: null };
  const alsoUnknown = { name: 'C', kind: 'Walking Track' };

  const shown = docShowing([unknown, known, alsoUnknown], [], 'nearest');

  assert.deepEqual(shown.map((t) => t.name), ['B', 'A', 'C']);
});

test('picking kinds keeps only those; none picked keeps them all', () => {
  const walk = { name: 'Walk', kind: 'Walking Track' };
  const tramp = { name: 'Tramp', kind: 'Tramping Track' };

  assert.deepEqual(docShowing([walk, tramp], ['Tramping Track'], 'name'), [tramp]);
  assert.equal(docShowing([walk, tramp], [], 'name').length, 2);
});

test('by name is A to Z, and the input is not reordered underneath the caller', () => {
  const b = { name: 'b', kind: 'Walking Track' };
  const a = { name: 'A', kind: 'Walking Track' };
  const original = [b, a];

  assert.deepEqual(docShowing(original, [], 'name').map((t) => t.name), ['A', 'b']);
  assert.deepEqual(original, [b, a], 'the page keeps its own array');
});
