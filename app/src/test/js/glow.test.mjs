/*
 * What is drawn under the asset that is picked out.
 *
 * `glow.mjs` is pure, so node runs the very file the browser does: what the mark is made of - its width,
 * its softness, its strength, its colour, and above all the *shape* it takes from the phone's own layer -
 * is tested here rather than read by eye in `app.js`.
 *
 * Two things are being held. The shape: a mark is built out of one of the phone's layers, and the one thing
 * it must not do is change what that layer says - a fenceline is dotted because a fenceline is a series of
 * short things, and an edge wide enough to fill in the gaps between the dots would draw it solid. And the
 * colour: the edge under a line is the page's own white, which none of the three due colours is, so a line
 * goes on saying when it is due while the edge says only "this is the one in hand".
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { NO_ASSET, haloId, haloLayer, pickOut } from '../../main/assets/web/glow.mjs';

/** The page's own paper: the white a line's edge is drawn in. */
const WHITE = '#ffffff';

/** The phone's own traffic light, from `AssetColors`: green, amber, red - and the edge may wear none of them. */
const DUE_COLOURS = ['#2E7D32', '#F9A825', '#C62828'];

/** A track's own layer, as `WebStyleJson` builds it: solid, 5 px, the feature's own colour. */
const track = {
  id: 'sprayday-assets-line-track',
  type: 'line',
  source: 'assets',
  filter: ['all', ['==', ['get', 'kind'], 'TRACK'], ['==', ['get', 'shape'], 'LINE']],
  paint: { 'line-color': ['get', 'stroke'], 'line-width': 5, 'line-opacity': 0.9 }
};

/** A road's: dashed, in the dashes the phone keeps for a road. */
const roadLayer = { ...track, id: 'sprayday-assets-line-road', paint: { ...track.paint, 'line-dasharray': [2, 1.6] } };

/** A fenceline's: dotted, which is a very short dash with a round cap rather than a dot. */
const fenceline = {
  ...track,
  id: 'sprayday-assets-line-infrastructure',
  paint: { ...track.paint, 'line-dasharray': [0.05, 1.7] }
};

/** A place's: a picture, so no width and no paint block at all. */
const place = {
  id: 'sprayday-assets-point',
  type: 'symbol',
  source: 'assets',
  filter: ['==', ['get', 'shape'], 'POINT']
};

test('a line that is picked out gets a white edge under it, wider than the line and barely softened', () => {
  const edge = haloLayer(track, 7);

  assert.equal(edge.type, 'line', 'the edge under a line is a line');
  assert.equal(edge.source, track.source, 'drawn from the same source as the layer it belongs under');
  assert.ok(edge.paint['line-width'] > track.paint['line-width'], 'wider than the line, or the line hides it');
  assert.equal(edge.paint['line-color'], WHITE, 'the page\'s own paper, not the line\'s colour');
  assert.ok(edge.paint['line-blur'] <= 3, 'an edge, not a haze: soft enough to spread is soft enough to vanish');
  assert.ok(edge.paint['line-opacity'] > 0.7, 'nearly solid, or the imagery reads straight through it');
});

test('the edge does not wear the line\'s own colour, so it cannot claim a state the line does not have', () => {
  const green = { ...track, paint: { ...track.paint } };
  const red = { ...track, paint: { ...track.paint } };

  const edgeOf = (layer, id) => haloLayer(layer, id).paint['line-color'];

  assert.equal(edgeOf(green, 7), edgeOf(red, 8), 'the same white for a track of any colour');
  assert.notDeepEqual(edgeOf(green, 7), ['get', 'stroke'], 'and not the feature\'s own colour');
  assert.ok(
    DUE_COLOURS.every((due) => due !== edgeOf(green, 7).toUpperCase()),
    'nor one of the three the traffic light uses, which would read as the line\'s own state'
  );
});

/** The dashes a layer draws, in pixels: a dasharray is in multiples of that layer's own width. */
const dashPixels = (layer) =>
  (layer.paint['line-dasharray'] || []).map((length) => length * layer.paint['line-width']);

test('a solid track gets a solid halo', () => {
  assert.equal(
    'line-dasharray' in haloLayer(track, 7).paint,
    false,
    'a track is solid, so its halo must not invent dashes'
  );
});

test('a dotted fenceline gets a dotted halo, and its dots land where the line\'s dots do', () => {
  const halo = haloLayer(fenceline, 7);

  assert.equal(halo.paint['line-dasharray'].length, 2, 'a dot and a gap: the phone\'s own pattern');
  const line = dashPixels(fenceline);
  const glow = dashPixels(halo);
  for (const i of [0, 1]) {
    assert.ok(
      Math.abs(line[i] - glow[i]) < 1e-6,
      `the halo's dash ${i} is ${glow[i]} px against the line's ${line[i]} - a halo whose dots fell in the ` +
        'gaps between the line\'s dots would read as a speckle around the track rather than as a glow on it'
    );
  }
});

test('a dashed road gets the road\'s own dashes, not a dash of the page\'s choosing', () => {
  const roadHalo = haloLayer(roadLayer, 7);

  assert.equal(roadHalo.paint['line-dasharray'].length, 2);
  assert.ok(roadHalo.paint['line-dasharray'][0] > roadHalo.paint['line-dasharray'][1], 'a dash longer than its gap');
});

test('the halo carries the phone\'s own filter for its layer, with this one asset added', () => {
  assert.deepEqual(haloLayer(track, 7).filter, [
    'all',
    ['==', ['get', 'kind'], 'TRACK'],
    ['==', ['get', 'shape'], 'LINE'],
    ['==', ['get', 'id'], 7]
  ]);
});

test('picking one asset out of a filter that is not an `all` is a filter that is, rather than a filter that is not', () => {
  assert.deepEqual(pickOut(['==', ['get', 'shape'], 'POINT'], 7), [
    'all',
    ['==', ['get', 'shape'], 'POINT'],
    ['==', ['get', 'id'], 7]
  ]);
  assert.deepEqual(pickOut(null, 7), ['==', ['get', 'id'], 7], 'a layer with no filter of its own still narrows');
});

test('nothing glows until an asset is picked out: the halo names an id no asset has', () => {
  const halo = haloLayer(track);

  assert.ok(NO_ASSET < 1, 'asset ids come from the phone and count up from one');
  assert.deepEqual(halo.filter[halo.filter.length - 1], ['==', ['get', 'id'], NO_ASSET]);
});

test('a place gets a soft disc of its own colour behind the house, not a white edge', () => {
  const disc = haloLayer(place, 7);

  assert.equal(disc.type, 'circle', 'a place is a picture, so there is no width to widen');
  assert.deepEqual(
    disc.paint['circle-color'],
    ['get', 'stroke'],
    'a house already has a white edge of its own, so another white one round it would say nothing'
  );
  assert.ok(disc.paint['circle-opacity'] > 0.2 && disc.paint['circle-opacity'] < 1, 'a light, not a disc');
  assert.ok(disc.paint['circle-radius'] > 8, 'bigger than the house drawn on top of it');
  assert.ok(disc.paint['circle-blur'] > 0 && disc.paint['circle-blur'] < 1, 'soft, but not blurred away');
});

test('the layer the phone sent is left exactly as it was', () => {
  const before = structuredClone(fenceline);

  const halo = haloLayer(fenceline, 7);

  assert.deepEqual(fenceline, before, 'nothing about the phone\'s own layer is written to');
  assert.notEqual(
    halo.paint['line-dasharray'],
    fenceline.paint['line-dasharray'],
    'and the dashes are copied, so a page that changed its own halo could not change the phone\'s layer'
  );
});

test('a line layer that says nothing about its width still gets a halo with one', () => {
  const halo = haloLayer({ ...track, paint: {} }, 7);

  assert.ok(halo.paint['line-width'] > 0, 'a halo of no width is a halo nobody sees');
});

test('the halo is named after the layer it belongs under, once', () => {
  assert.equal(haloId(track.id), 'sprayday-desk-glow-sprayday-assets-line-track');
  assert.equal(haloLayer(track, 7).id, haloId(track.id));
});
