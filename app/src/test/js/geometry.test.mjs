/*
 * The desk's drawing, without a browser.
 *
 * `geometry.mjs` is pure on purpose: what it needs from a map and from the page arrives as arguments,
 * so the whole thing runs here, under node, with a projection that is arithmetic. What is being held
 * is the part of the desk's drawing a screenshot cannot show - the history behind Ctrl+Z, the tolerance
 * a click has to be inside, and the exact numbers a snapped vertex takes - plus the one GeoJSON trap
 * that would put a track in the Southern Alps if it were got wrong, coordinates being [lng, lat] while
 * the phone's own vertices are lat-first.
 *
 * Run it with `node --test app/src/test/js`, which is what CI does.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  HISTORY_LIMIT,
  add,
  canRedo,
  canUndo,
  createPath,
  insert,
  move,
  redo,
  remove,
  samePlace,
  segmentAt,
  snap,
  toFeature,
  undo,
  vertex,
  vertexAt,
  verticesOf
} from '../../main/assets/web/geometry.mjs';

/** A projection with no map under it: a degree is 1000 pixels, so the arithmetic is visible. */
const project = (point) => ({ x: point.lng * 1000, y: point.lat * 1000 });

const a = { lat: -41.5, lng: 173.8 };
const b = { lat: -41.6, lng: 173.9 };
const c = { lat: -41.7, lng: 174.0 };

test('a line just started has nothing to undo and nothing to redo', () => {
  const path = createPath();

  assert.deepEqual(path.points, []);
  assert.equal(canUndo(path), false);
  assert.equal(canRedo(path), false);
  assert.equal(undo(path), path, 'undoing nothing hands back the same line');
});

test('a vertex carries the two numbers the phone stores, and nothing a map event adds', () => {
  const path = add(createPath(), { ...a, x: 620, y: 331, bearing: 12 });

  assert.deepEqual(path.points, [a]);
  assert.equal('x' in path.points[0], false);
});

test('every step can be walked back and forward again, exactly', () => {
  let path = add(createPath(), a);
  path = add(path, b);
  path = add(path, c);

  assert.deepEqual(path.points, [a, b, c]);

  path = undo(path);
  assert.deepEqual(path.points, [a, b]);
  path = undo(path);
  assert.deepEqual(path.points, [a]);

  path = redo(path);
  assert.deepEqual(path.points, [a, b]);
  path = redo(path);
  assert.deepEqual(path.points, [a, b, c], 'and forward again to the same place, to the bit');

  assert.equal(canRedo(path), false, 'nothing left to redo');
});

test('a new step after an undo is a new history, and what was undone is gone', () => {
  let path = createPath();
  path = add(path, a);
  path = add(path, b);
  path = add(path, c);
  path = undo(path);
  assert.deepEqual(path.points, [a, b]);
  assert.equal(canRedo(path), true, 'the step that was undone is still there to go forward to');

  path = remove(path, 0);

  assert.deepEqual(path.points, [b]);
  assert.equal(canRedo(path), false, 'the branch that was undone is not reachable any more');
});

test('a drag that ends where it started is not a step', () => {
  // Which is what a click on a handle without moving it is: without this, tidying a track would leave
  // the operator pressing Ctrl+Z twice to undo one thing.
  const path = createPath([a, b]);

  const same = move(path, 0, { ...a });

  assert.equal(same, path, 'and the line it hands back is the very same line');
  assert.equal(canUndo(same), false);
});

test('undoing a drag puts the vertex back where it was', () => {
  const path = move(createPath([a, b]), 1, c);

  assert.deepEqual(path.points, [a, c]);
  assert.deepEqual(undo(path).points, [a, b]);
});

test('a moved vertex is where it was dropped, and a vertex off the end of the line is nothing', () => {
  const path = createPath([a, b]);

  assert.deepEqual(move(path, 1, c).points, [a, c], 'the index is the vertex being dragged');
  assert.equal(move(path, 2, c), path, 'a handle that is not there moves nothing');
  assert.equal(move(path, -1, c), path);
  assert.equal(remove(path, 5), path);
});

test('a vertex is inserted between the two it was clicked between', () => {
  const path = createPath([a, b, c]);
  // A click on the middle of the second segment: `segmentAt` says where it goes, `insert` puts it there.
  const click = { x: (project(b).x + project(c).x) / 2, y: (project(b).y + project(c).y) / 2 };

  const at = segmentAt(path.points, click, project);

  assert.equal(at, 2);
  assert.deepEqual(insert(path, at, { lat: -41.65, lng: 173.95 }).points, [
    a,
    b,
    { lat: -41.65, lng: 173.95 },
    c
  ]);
});

test('a click has to be near the line to be on it, in pixels rather than degrees', () => {
  const path = createPath([a, b]);

  // The midpoint of the line, a few pixels to one side of it: near enough.
  const on = { x: (project(a).x + project(b).x) / 2, y: (project(a).y + project(b).y) / 2 + 3 };
  assert.equal(segmentAt(path.points, on, project), 1);

  // Twenty pixels away is a click on the paddock, not on the track.
  const off = { x: on.x, y: on.y + 20 };
  assert.equal(segmentAt(path.points, off, project), -1);

  // And a line of one vertex has no segment to click on at all.
  assert.equal(segmentAt([a], on, project), -1);
});

test('a handle is found by a click near it, and a click in the paddock finds nothing', () => {
  const points = [a, b];

  assert.equal(vertexAt(points, { x: project(b).x + 4, y: project(b).y - 4 }, project), 1);
  assert.equal(vertexAt(points, { x: project(b).x + 40, y: project(b).y }, project), -1);
  assert.equal(vertexAt([], { x: 0, y: 0 }, project), -1);
});

test('snapping copies the vertex it snapped to, exactly', () => {
  // The whole reason: two vertices that are meant to be the same place have to be the same two
  // numbers, or the phone sees two vertices a hair apart and keeps both.
  const neighbours = [{ lat: -41.512345, lng: 173.812345 }];
  const near = { lat: -41.5123, lng: 173.8123 };

  const snapped = snap(near, neighbours, project);

  assert.deepEqual(snapped, neighbours[0]);
  assert.equal(samePlace(snapped, neighbours[0]), true);
});

test('snapping leaves a click alone when there is nothing near it', () => {
  const neighbours = [{ lat: -41.512345, lng: 173.812345 }];

  assert.equal(snap({ lat: -41.6, lng: 173.9 }, neighbours, project), null);
  assert.equal(snap({ lat: -41.6, lng: 173.9 }, [], project), null, 'nothing to snap onto');
});

test('a snapped loop closes onto the vertex the line started at', () => {
  // The caller offers the line's own first vertex as a candidate, which is how a track that comes back
  // to where it started is closed exactly rather than nearly.
  const path = createPath([a, b]);
  const near = { lat: a.lat + 0.000002, lng: a.lng + 0.000002 };

  const snapped = snap(near, [path.points[0]], project);

  assert.deepEqual(snapped, a);
  assert.deepEqual(add(path, snapped).points, [a, b, a]);
});

test('the line the map draws is a point of one vertex, a string of many, and nothing of none', () => {
  assert.deepEqual(toFeature([]), { type: 'FeatureCollection', features: [] });
  assert.deepEqual(toFeature([a]).features[0].geometry, {
    type: 'Point',
    coordinates: [a.lng, a.lat]
  });
  assert.deepEqual(toFeature([a, b]).features[0].geometry, {
    type: 'LineString',
    coordinates: [
      [a.lng, a.lat],
      [b.lng, b.lat]
    ]
  });
});

test("another asset's vertices are read lat-first, out of a document that is lng-first", () => {
  // The trap this exists for: GeoJSON is [lng, lat] and the phone's own vertices are lat-first, so
  // getting it the wrong way round puts a Waikato track in the Southern Alps - and it would still
  // look like a line on the map.
  const feature = {
    type: 'Feature',
    properties: { id: 3 },
    geometry: {
      type: 'LineString',
      coordinates: [
        [173.8, -41.5],
        [173.9, -41.6]
      ]
    }
  };

  assert.deepEqual(verticesOf(feature), [a, b]);
  assert.deepEqual(
    verticesOf({ geometry: { type: 'Point', coordinates: [173.8, -41.5] } }),
    [a],
    'a place is one vertex, which is somewhere to snap a track onto'
  );
  assert.deepEqual(verticesOf({}), [], 'and nothing to read is nothing to snap to');
});

test('the history is capped, and the oldest step is the one forgotten', () => {
  let path = createPath();
  for (let index = 0; index <= HISTORY_LIMIT + 5; index++) {
    path = add(path, { lat: -41.5, lng: 173.8 + index * 0.001 });
  }

  assert.equal(path.past.length, HISTORY_LIMIT);
  // Walk all the way back: what is reached is the oldest step kept rather than the empty line, which
  // is the honest behaviour of a capped history - a step that fell off the end is forgotten.
  for (let step = 0; step < HISTORY_LIMIT; step++) path = undo(path);

  assert.equal(canUndo(path), false);
  // Six, not one: the steps that fell off the end are the empty line and the first five vertices, and
  // what is reached is the line as it stood after the sixth. The oldest step is gone, as a capped
  // history means it to be - and six is pinned here so that a change to the cap is seen here first.
  assert.equal(path.points.length, 6, 'the line as it stood six steps in');
});

test('a path handed in comes back vertex by vertex, and the caller keeps its own array', () => {
  const original = [a, b];
  const path = createPath(original);

  add(path, c);

  assert.equal(original.length, 2, 'the array the operator clicked from is not written to');
  assert.deepEqual(vertex(a), a);
});
