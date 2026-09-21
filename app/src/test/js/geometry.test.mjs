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
  TRACE_PX,
  add,
  canRedo,
  canUndo,
  createPath,
  insert,
  metresPerPixel,
  move,
  redo,
  remove,
  samePlace,
  segmentAt,
  simplify,
  snap,
  toFeature,
  trace,
  traced,
  undo,
  vertex,
  vertexAt,
  verticesOf
} from '../../main/assets/web/geometry.mjs';

/** A projection with no map under it: a degree is 1000 pixels, so the arithmetic is visible. */
const project = (point) => ({ x: point.lng * 1000, y: point.lat * 1000 });

/** Metres in a degree of latitude: the page's own mean, repeated here so a test can measure in metres. */
const METRES_PER_DEGREE_TEST = 111_320;

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

/* ---- Tracing: the line that follows the pointer -------------------------------------- */

test('a traced point is kept once the pointer has moved, and dropped when it has not', () => {
  // The projection above is a degree to a thousand pixels, so this is travel in whole pixels.
  let stroke = trace([], a, project);
  assert.deepEqual(stroke, [a]);

  stroke = trace(stroke, { lat: a.lat, lng: a.lng + 0.0005 }, project);
  assert.equal(stroke.length, 1, 'half a pixel of travel is the hand, not the fence');

  stroke = trace(stroke, { lat: a.lat, lng: a.lng + 0.005 }, project);
  assert.equal(stroke.length, 2, 'five pixels of travel is a point on the fence');

  // Exactly the tolerance is a point: the rule is "moved at least this far", not "more than".
  stroke = trace(stroke, { lat: a.lat, lng: a.lng + 0.005 + TRACE_PX / 1000 }, project);
  assert.equal(stroke.length, 3);
});

test('a traced point is never a repeat of the one before it, to the bit', () => {
  const stroke = trace([a], { lat: a.lat, lng: a.lng }, project);

  assert.equal(stroke.length, 1, 'the phone drops consecutive repeats, so none are gathered here');
});

test('a straight wobble simplifies to the points that carry its shape', () => {
  const midway = { lat: a.lat, lng: a.lng + 0.0002 };
  const end = { lat: a.lat, lng: a.lng + 0.0004 };

  const kept = simplify([a, midway, end], 1);

  assert.deepEqual(kept, [a, end], 'a point on the line carries nothing');
});

test('a corner survives simplification exactly where it was traced', () => {
  const east = { lat: a.lat, lng: a.lng + 0.001 };
  const south = { lat: a.lat - 0.001, lng: east.lng };
  const wobble = { lat: a.lat, lng: a.lng + 0.0005 };

  const kept = simplify([a, wobble, east, south], 5);

  assert.deepEqual(kept, [a, east, south], 'the corner is the shape, and it is kept');
  assert.deepEqual(kept[1], east, 'and kept as the very vertex that was traced, not moved onto the line');
});

test('a tolerance of nothing keeps every point that deviates at all', () => {
  const onTheLine = { lat: a.lat, lng: a.lng + 0.0002 };
  const end = { lat: a.lat, lng: a.lng + 0.0004 };

  assert.deepEqual(
    simplify([a, onTheLine, end], 0),
    [a, end],
    'a point exactly on the line is not a point, whatever the tolerance'
  );

  // A tenth of a millimetre off it is: with nothing to throw away, nothing is thrown away.
  const hair = { lat: a.lat + 0.000000001, lng: a.lng + 0.0002 };
  assert.deepEqual(simplify([a, hair, end], 0), [a, hair, end]);
});

test('a traced fence goes onto the line as one step, and one Ctrl+Z takes it all back', () => {
  // The view it was traced in, as ground distances: at zoom 17 over the farm a pixel is about 0.9 m, so
  // the hand samples every 3.6 m and wobbles by about 0.9 m - both under the tolerance, which is what
  // the fifty-two samples below are here to show.
  const perPixel = metresPerPixel(a.lat, 17);
  const degreesLat = (metres) => metres / METRES_PER_DEGREE_TEST;
  const degreesLng = (metres) =>
    metres / (METRES_PER_DEGREE_TEST * Math.cos((a.lat * Math.PI) / 180));

  const stroke = [];
  for (let sample = 1; sample <= 26; sample++) {
    stroke.push({
      lat: a.lat + degreesLat(sample % 2 === 0 ? perPixel : -perPixel),
      lng: a.lng + degreesLng(sample * TRACE_PX * perPixel)
    });
  }
  const corner = stroke[stroke.length - 1];
  for (let sample = 1; sample <= 26; sample++) {
    stroke.push({
      lat: corner.lat - degreesLat(sample * TRACE_PX * perPixel),
      lng: corner.lng + degreesLng(sample % 2 === 0 ? perPixel : -perPixel)
    });
  }

  const before = add(createPath(), a);
  const path = traced(before, stroke, perPixel * (TRACE_PX / 2));

  assert.equal(path.points.length, 3, 'the fence, its corner, and where the arm stopped');
  assert.deepEqual(path.points[1], corner, 'the corner is where it was traced');
  assert.equal(path.past.length, before.past.length + 1, 'fifty-two samples are one thing to take back');
  assert.deepEqual(undo(path).points, before.points, 'and Ctrl+Z leaves the line as it was before the trace');
});

test('a press that never moved is not a step at all', () => {
  const path = add(createPath(), a);

  assert.equal(traced(path, [], 1), path, 'nothing traced, nothing to take back');
  assert.equal(traced(path, [b], 1), path, 'one point is a press: the click that follows adds it');
  assert.equal(
    traced(path, [b, c], 1).past.length,
    path.past.length + 1,
    'two points is a trace'
  );
});

test('a trace that comes back to where it started closes on that vertex exactly', () => {
  const path = add(createPath(), a);
  const closed = add(path, b);

  // Snapping is the page's, and what it hands over is the line's own first vertex, copied.
  const after = traced(closed, [c, vertex(a)], 1);

  assert.deepEqual(after.points[after.points.length - 1], a);
  assert.ok(samePlace(after.points[0], after.points[after.points.length - 1]));
});

test('a traced line is simplified to the size of what the operator could see', () => {
  // Zoom 17 over the farm: about 0.9 m to a pixel, so half the sampling step is a couple of metres of
  // fence - the shape as it looked on the screen, and not the shape of the hand.
  const perPixel = metresPerPixel(-41.5, 17);
  assert.ok(perPixel > 0.85 && perPixel < 0.95, `a pixel at zoom 17 is about 0.9 m: ${perPixel}`);
  assert.ok(
    perPixel * (TRACE_PX / 2) > 1.5 && perPixel * (TRACE_PX / 2) < 2,
    'and the tolerance is a metre or two of fence'
  );

  // Zooming out doubles what a pixel is worth, which is what makes the tolerance follow the view: the
  // same wobble traced further out is a bigger wobble on the ground.
  assert.equal(metresPerPixel(-41.5, 16), perPixel * 2);
  assert.equal(metresPerPixel(0, 0), 40075016.686 / 256, 'a pixel at zoom 0 is the world over 256');
  assert.ok(metresPerPixel(-41.5, 17) < metresPerPixel(0, 17), 'and a pixel shrinks towards the pole');
});
