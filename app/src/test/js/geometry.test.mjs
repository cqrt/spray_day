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
  activePath,
  areaSqm,
  backToLine,
  corners,
  createPaths,
  dropSideTrack,
  hold,
  junctionFeature,
  lengthMeters,
  otherPathAt,
  pathsFeature,
  perimeterMeters,
  ring,
  sideTrackInHand,
  startSideTrack,
  HISTORY_LIMIT,
  RING_CORNERS,
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

  assert.deepEqual(activePath(path), []);
  assert.equal(canUndo(path), false);
  assert.equal(canRedo(path), false);
  assert.equal(undo(path), path, 'undoing nothing hands back the same line');
});

test('a vertex carries the two numbers the phone stores, and nothing a map event adds', () => {
  const path = add(createPath(), { ...a, x: 620, y: 331, bearing: 12 });

  assert.deepEqual(activePath(path), [a]);
  assert.equal('x' in activePath(path)[0], false);
});

test('every step can be walked back and forward again, exactly', () => {
  let path = add(createPath(), a);
  path = add(path, b);
  path = add(path, c);

  assert.deepEqual(activePath(path), [a, b, c]);

  path = undo(path);
  assert.deepEqual(activePath(path), [a, b]);
  path = undo(path);
  assert.deepEqual(activePath(path), [a]);

  path = redo(path);
  assert.deepEqual(activePath(path), [a, b]);
  path = redo(path);
  assert.deepEqual(activePath(path), [a, b, c], 'and forward again to the same place, to the bit');

  assert.equal(canRedo(path), false, 'nothing left to redo');
});

test('a new step after an undo is a new history, and what was undone is gone', () => {
  let path = createPath();
  path = add(path, a);
  path = add(path, b);
  path = add(path, c);
  path = undo(path);
  assert.deepEqual(activePath(path), [a, b]);
  assert.equal(canRedo(path), true, 'the step that was undone is still there to go forward to');

  path = remove(path, 0);

  assert.deepEqual(activePath(path), [b]);
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

  assert.deepEqual(activePath(path), [a, c]);
  assert.deepEqual(activePath(undo(path)), [a, b]);
});

test('a moved vertex is where it was dropped, and a vertex off the end of the line is nothing', () => {
  const path = createPath([a, b]);

  assert.deepEqual(activePath(move(path, 1, c)), [a, c], 'the index is the vertex being dragged');
  assert.equal(move(path, 2, c), path, 'a handle that is not there moves nothing');
  assert.equal(move(path, -1, c), path);
  assert.equal(remove(path, 5), path);
});

test('a vertex is inserted between the two it was clicked between', () => {
  const path = createPath([a, b, c]);
  // A click on the middle of the second segment: `segmentAt` says where it goes, `insert` puts it there.
  const click = { x: (project(b).x + project(c).x) / 2, y: (project(b).y + project(c).y) / 2 };

  const at = segmentAt(activePath(path), click, project);

  assert.equal(at, 2);
  assert.deepEqual(activePath(insert(path, at, { lat: -41.65, lng: 173.95 })), [
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
  assert.equal(segmentAt(activePath(path), on, project), 1);

  // Twenty pixels away is a click on the paddock, not on the track.
  const off = { x: on.x, y: on.y + 20 };
  assert.equal(segmentAt(activePath(path), off, project), -1);

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

  const snapped = snap(near, [activePath(path)[0]], project);

  assert.deepEqual(snapped, a);
  assert.deepEqual(activePath(add(path, snapped)), [a, b, a]);
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

/**
 * A hundred metres of latitude, and of longitude at this corner: a paddock of a size a test can
 * measure. The phone's own two constants, so the desk's figure is the phone's figure.
 */
const LAT_100M = 100 / 111_132;
const LNG_100M = 100 / (111_320 * Math.cos((41.5 * Math.PI) / 180));
const yardCorner = { lat: -41.5, lng: 173.8 };
const yard = [
  yardCorner,
  { lat: -41.5, lng: 173.8 + LNG_100M },
  { lat: -41.5 - LAT_100M, lng: 173.8 + LNG_100M },
  { lat: -41.5 - LAT_100M, lng: 173.8 }
];

test('a ring is opened for editing on its corners, not on the corner repeated at its end', () => {
  // How a ring is stored: the first corner again as the last vertex, so everything that reads the
  // geometry afterwards gets the closing side without asking what shape it is holding. The desk takes
  // that repeat off, because a handle on it is a point that can be dragged away from the boundary it
  // closes - one carpark turning into a carpark and a stray line, with nothing on the screen to say so.
  assert.deepEqual(corners([...yard, yardCorner]), yard);
  assert.deepEqual(corners(yard), yard);
  assert.deepEqual(corners([]), []);
});

test('a ring is drawn with its closing side, and nothing is closed before there is ground inside', () => {
  // The phone's own least for a ring (`Ring.MIN_CORNERS`), and the page's.
  assert.equal(RING_CORNERS, 3);

  const drawn = toFeature(yard, true).features[0].geometry.coordinates;
  assert.equal(drawn.length, yard.length + 1);
  assert.deepEqual(drawn[drawn.length - 1], drawn[0]);

  // Two points enclose nothing, so nothing is closed: the phone refuses that save, and drawing a
  // boundary it is about to refuse would be the page showing something the phone does not hold.
  assert.equal(ring(yard.slice(0, 2)).length, 2);
  assert.equal(toFeature(yard.slice(0, 2), true).features[0].geometry.coordinates.length, 2);
  // And with the flag off, every caller from before this keeps exactly what it had.
  assert.equal(toFeature(yard).features[0].geometry.coordinates.length, yard.length);
});

test('only the boundary is closed, so a line with side tracks is drawn as it always was', () => {
  const spur = [yard[1], { lat: -41.4, lng: 173.9 }];
  const drawn = pathsFeature([yard, spur], true);

  assert.equal(drawn.features.length, 2);
  assert.equal(drawn.features[0].geometry.coordinates.length, yard.length + 1);
  assert.equal(drawn.features[1].geometry.coordinates.length, spur.length);
});

test('the ground inside a ring is measured the way the phone measures it', () => {
  // A hundred-metre square on the flat the phone works its own area out on: ten thousand square
  // metres. This is the number the app has never been able to take off a line - only to estimate one
  // from a swath width somebody typed - and the desk previewing a different one would be showing a
  // figure the phone replaces the moment it is asked to save.
  const measured = areaSqm(yard);
  assert.ok(Math.abs(measured - 10_000) < 100, `a 100 m square is 10,000 m², not ${measured}`);

  // The corners and the stored ring are the same ground, whichever way round it is walked.
  assert.equal(areaSqm([...yard, yardCorner]), measured);
  assert.equal(areaSqm([...yard].reverse()), measured);
  // And nothing is enclosed by two points, or by one.
  assert.equal(areaSqm(yard.slice(0, 2)), 0);
  assert.equal(areaSqm(yard.slice(0, 1)), 0);
});

test('the metres round a ring include the closing side', () => {
  const around = perimeterMeters(yard);

  assert.ok(Math.abs(around - 400) < 4, `four sides of 100 m is 400 m, not ${around}`);
  // The difference from walking the corners alone is the side the operator did not click: the one the
  // phone adds when it stores the ring, and the one a rate per metre has to be worked out from.
  const laid = lengthMeters([yard]);
  assert.ok(Math.abs(around - laid - 100) < 1, `${around} less ${laid} is not the closing side`);
  // Below three corners nothing is closed, so there is nothing extra to count.
  assert.equal(perimeterMeters(yard.slice(0, 2)), lengthMeters([yard.slice(0, 2)]));
  assert.equal(perimeterMeters(yard.slice(0, 1)), 0);
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
  // Ground, as the phone serves it: a Polygon whose ring is closed on itself, one level deeper than a
  // line's coordinates. This is the shape that fitted the desk's camera to [NaN, NaN] on every carpark
  // picked from the list, and that snapping could not see at all.
  const yard = {
    type: 'Feature',
    properties: { id: 4 },
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [173.8, -41.5],
          [173.9, -41.5],
          [173.9, -41.6],
          [173.8, -41.5]
        ]
      ]
    }
  };

  assert.deepEqual(
    verticesOf(yard),
    [a, { lat: -41.5, lng: 173.9 }, b, a],
    'a ring: its corners in order, closing vertex and all'
  );
  assert.deepEqual(
    verticesOf({
      geometry: {
        type: 'MultiLineString',
        coordinates: [
          [
            [173.8, -41.5],
            [173.9, -41.6]
          ]
        ]
      }
    }),
    [a, b],
    'and every part of a shape that travels in more than one piece'
  );
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
  assert.equal(activePath(path).length, 6, 'the line as it stood six steps in');
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

  assert.equal(activePath(path).length, 3, 'the fence, its corner, and where the arm stopped');
  assert.deepEqual(activePath(path)[1], corner, 'the corner is where it was traced');
  assert.equal(path.past.length, before.past.length + 1, 'fifty-two samples are one thing to take back');
  assert.deepEqual(activePath(undo(path)), activePath(before), 'and Ctrl+Z leaves the line as it was before the trace');
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

  assert.deepEqual(activePath(after)[activePath(after).length - 1], a);
  assert.ok(samePlace(activePath(after)[0], activePath(after)[activePath(after).length - 1]));
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

/* ---- Side tracks: a line with strips hanging off it ----------------------------------- */

/** A side track's far end: the strip hangs off `b`, which is the far end of the line `a`-`b`. */
const spurEnd = { lat: -41.7, lng: 173.9 };

test('a side track starts at the end of the line, on the line\'s own vertex', () => {
  const drawing = startSideTrack(createPath([a, b]));

  assert.equal(drawing.paths.length, 2, 'the line and the side track');
  assert.deepEqual(drawing.paths[1], [b], 'the side track starts as the junction, and nothing else');
  assert.equal(drawing.active, 1, 'and the clicks go to it');
  // The same vertex to the bit, not a place near it: that is what the phone's own rules ask for, because
  // "these two paths meet" is a comparison of the two numbers rather than of an intention.
  assert.deepEqual(drawing.paths[1][0], drawing.paths[0][1]);
});

test('what is drawn next goes on the side track, and the line is left alone', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);

  assert.deepEqual(activePath(drawing), [b, spurEnd]);
  assert.deepEqual(drawing.paths[0], [a, b], 'the line is exactly what it was');
});

test('a side track that never got a second point is taken off by going back to the line', () => {
  const drawing = backToLine(startSideTrack(createPath([a, b])));

  assert.equal(drawing.paths.length, 1, 'one click is not a side track');
  assert.equal(drawing.active, 0, 'and the clicks are back on the line');
});

test('a side track with something on it survives going back to the line', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  assert.equal(drawing.paths.length, 2);
  assert.deepEqual(drawing.paths[1], [b, spurEnd]);
  assert.equal(drawing.active, 0);

  // And the line carries on from the junction, which is where it ended: the spur is a strip beside it
  // rather than a detour on it.
  drawing = add(drawing, c);
  assert.deepEqual(drawing.paths[0], [a, b, c]);
  assert.deepEqual(drawing.paths[1], [b, spurEnd], 'the side track kept what it had');
});

test('Ctrl+Z after starting a side track hands the line back', () => {
  let drawing = startSideTrack(createPath([a, b]));
  const withSideTrack = drawing;

  drawing = undo(drawing);

  assert.equal(drawing.active, 0, 'the path being worked on is part of the state');
  assert.equal(drawing.paths.length, 1);
  assert.deepEqual(activePath(drawing), [a, b]);

  drawing = redo(drawing);
  assert.equal(redo(withSideTrack).paths.length, 2, 'and forward again is the side track');
  assert.deepEqual(drawing.paths[1], [b]);
});

test('the side track being drawn can be taken off, line and all intact', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = dropSideTrack(drawing);

  assert.equal(drawing.paths.length, 1);
  assert.deepEqual(drawing.paths[0], [a, b]);
  assert.equal(drawing.active, 0);
  assert.equal(dropSideTrack(createPath([a, b])).paths.length, 1, 'and nothing to drop is nothing');
});

test('a line with nothing to hang off cannot start a side track', () => {
  assert.equal(startSideTrack(createPath([a])).paths.length, 1);
  assert.equal(startSideTrack(createPath([])).paths.length, 0);
});

test('a side track does not offer the line\'s first vertex to snap onto', () => {
  // A side track that closed back onto the far end of the line would be a loop rather than a spur, and
  // the phone's rules would refuse it: what snapping offers is the caller's, and this is the reason.
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);

  assert.equal(drawing.paths[1].length, 2);
  assert.notDeepEqual(drawing.paths[1][1], a);
});

test('taking the line vertex a side track hangs off takes the side track with it', () => {
  // A side track's first vertex is the line's junction, so a line without that vertex leaves an orphan -
  // and an orphan is a path the phone refuses rather than one it stores.
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  const after = remove(drawing, 1);

  assert.equal(after.paths.length, 1, 'the strip came off with the corner it hung on');
  assert.deepEqual(after.paths[0], [a]);
  assert.deepEqual(undo(after).paths[1], [b, spurEnd], 'and one Ctrl+Z brings both back');
});

test('dragging the line vertex a side track hangs off takes the junction with it', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  const moved = move(drawing, 1, c);

  assert.deepEqual(moved.paths[0], [a, c]);
  assert.deepEqual(moved.paths[1], [c, spurEnd], 'the two paths still meet, exactly');
});

test('dragging a line vertex nothing hangs off leaves the side tracks alone', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  const moved = move(drawing, 0, { lat: -41.45, lng: 173.75 });

  assert.deepEqual(moved.paths[0][0], { lat: -41.45, lng: 173.75 });
  assert.deepEqual(moved.paths[1], [b, spurEnd]);
});

test('taking the junction off a side track takes the strip off', () => {
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);

  const after = remove(drawing, 0);

  assert.equal(after.paths.length, 1, 'a strip with no start is not a strip');
  assert.deepEqual(after.paths[0], [a, b]);
  assert.equal(after.active, 0, 'and the line is what is in hand again');
});

test('an empty path is not a path', () => {
  assert.equal(createPaths([[], [a, b], []]).paths.length, 1);
});

test('the length counts every path once, which is the number the bar shows', () => {
  const line = [a, b];
  const spur = [b, spurEnd];
  const whole = lengthMeters([line, spur]);
  // What the old up-and-back way of drawing a spur would have produced: down it and back up it, inside
  // the one line - the shape that made an asset's length wrong on the phone too.
  const asOneLine = lengthMeters([[a, b, spurEnd, b]]);

  assert.ok(whole < asOneLine, `${Math.round(whole)} m against ${Math.round(asOneLine)} m`);
  assert.equal(
    Math.round(asOneLine - whole),
    Math.round(lengthMeters([spur])),
    'and the difference is exactly the spur walked twice'
  );
  assert.equal(Math.round(whole), Math.round(lengthMeters([line]) + lengthMeters([spur])));
});

test('a side track can leave the track from a point picked out on it', () => {
  const linePoints = [a, b, c];
  const drawing = startSideTrack(createPath(linePoints), 1);

  assert.equal(drawing.paths.length, 2);
  assert.deepEqual(drawing.paths[1], [b], 'the vertex that was picked, not the end of the track');
  assert.deepEqual(drawing.paths[1][0], drawing.paths[0][1], 'the line\'s own vertex, to the bit');
  assert.deepEqual(drawing.paths[0], linePoints, 'and the line is untouched, not split in two');
  assert.equal(drawing.active, 1, 'the clicks go to the new side track');
});

test('a side track leaving from the middle leaves the rest of the line alone', () => {
  let drawing = startSideTrack(createPath([a, b, c]), 1);
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);
  drawing = add(drawing, { lat: -41.75, lng: 173.75 });

  assert.deepEqual(drawing.paths[0], [a, b, c, { lat: -41.75, lng: 173.75 }]);
  assert.deepEqual(drawing.paths[1], [b, spurEnd]);
});

test('dragging the point a side track left from takes the side track with it', () => {
  let drawing = startSideTrack(createPath([a, b, c]), 1);
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  const moved = move(drawing, 1, { lat: -41.55, lng: 173.85 });

  assert.deepEqual(moved.paths[0][1], { lat: -41.55, lng: 173.85 });
  assert.deepEqual(moved.paths[1], [{ lat: -41.55, lng: 173.85 }, spurEnd], 'still met, exactly');
});

test('a picked point that is not there any more falls back to the end of the track', () => {
  // What an Undo between picking a point and using it does: the index has nothing under it, and a side
  // track that starts nowhere is not something the phone will take.
  const drawing = startSideTrack(createPath([a, b]), 7);

  assert.deepEqual(drawing.paths[1], [b]);
  assert.deepEqual(startSideTrack(createPath([a, b]), -1).paths[1], [b]);
});

test('the junction the map is shown is a place, lng first, and nothing when there is none', () => {
  const feature = junctionFeature(b);

  assert.deepEqual(feature.geometry.coordinates, [b.lng, b.lat]);
  assert.equal(feature.geometry.type, 'Point');
  assert.equal(feature.properties.junction, true, 'so the drawing\'s own layers can pick it out');
  assert.equal(junctionFeature(null), null);
});

test('a click on another path takes hold of it, and changes nothing about the drawing', () => {
  // The report this answers: "can delete and move points on a main track but not on side tracks". The
  // handles belong to the path in hand, so a side track has to be able to *become* the path in hand.
  let drawing = startSideTrack(createPath([a, b]));
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);

  const held = hold(drawing, 1);

  assert.equal(held.active, 1, 'the side track is in hand');
  assert.equal(sideTrackInHand(held), true);
  assert.deepEqual(held.paths, drawing.paths, 'and the drawing is exactly what it was');
  assert.equal(held.past.length, drawing.past.length, 'taking hold is not a step of the history');
  assert.equal(
    undo(held).paths.length,
    drawing.past[drawing.past.length - 1].paths.length,
    'so Ctrl+Z still takes back the last *change*'
  );
  assert.equal(hold(held, 9), held, 'and a path that is not there is not taken hold of');
  assert.equal(hold(held, 1), held, 'nor is the one already in hand');
});

test('with a side track in hand, its own points are the ones edited', () => {
  let drawing = startSideTrack(createPath([a, b, c]), 1);
  drawing = add(drawing, spurEnd);
  drawing = add(drawing, { lat: -41.72, lng: 173.92 });
  drawing = backToLine(drawing);
  drawing = hold(drawing, 1);

  // Del takes the end of the side track off, and the line is untouched.
  const trimmed = remove(drawing, 2);
  assert.deepEqual(trimmed.paths[1], [b, spurEnd]);
  assert.deepEqual(trimmed.paths[0], [a, b, c], 'the line did not move');

  // And a drag moves a side track's own vertex without touching the line.
  const moved = move(drawing, 1, { lat: -41.6, lng: 173.98 });

  assert.deepEqual(moved.paths[1], [b, { lat: -41.6, lng: 173.98 }, { lat: -41.72, lng: 173.92 }]);
  assert.deepEqual(moved.paths[0], [a, b, c]);
  assert.equal(moved.active, 1, 'and the side track is still the path in hand');
});

test('dragging the point a side track hangs off moves the line, because that point is the line\'s', () => {
  let drawing = startSideTrack(createPath([a, b, c]), 1);
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);
  drawing = hold(drawing, 1);
  // A second side track off the same junction, to prove everything hanging off it travels together.
  let two = startSideTrack(drawing, 1);
  two = add(two, { lat: -41.65, lng: 173.95 });
  two = backToLine(two);
  two = startSideTrack(two, 1);
  two = add(two, { lat: -41.62, lng: 173.96 });
  two = backToLine(two);
  two = hold(two, 1);

  const moved = move(two, 0, { lat: -41.55, lng: 173.85 });

  assert.deepEqual(moved.paths[0], [a, { lat: -41.55, lng: 173.85 }, c], 'the line vertex moved');
  assert.deepEqual(moved.paths[1][0], { lat: -41.55, lng: 173.85 }, 'and this side track with it');
  assert.deepEqual(moved.paths[2][0], { lat: -41.55, lng: 173.85 }, 'and the other one');
  assert.equal(moved.active, 1, 'with the side track still in hand');
});

test('a click beside the path in hand lands on the other one, and one on it does not', () => {
  const paths = [
    [a, b],
    [b, spurEnd]
  ];
  // A pixel grid of 1000 per degree, as the other tests use: the spur runs from b south to spurEnd.
  const onTheSpur = { x: spurEnd.lng * 1000, y: spurEnd.lat * 1000 };
  const onTheLine = { x: b.lng * 1000, y: b.lat * 1000 };

  assert.equal(otherPathAt(paths, 0, onTheSpur, project), 1, 'the side track, from the line being in hand');
  assert.equal(otherPathAt(paths, 1, onTheSpur, project), -1, 'and nothing when it is already in hand');
  assert.equal(otherPathAt(paths, 1, onTheLine, project), 0, 'the line, from the side track being in hand');
  assert.equal(otherPathAt(paths, 0, { x: 9000, y: 9000 }, project), -1, 'and nothing out in the paddock');
});

test('a side track Del-ed down to one point comes off whole, and hands the line back', () => {
  // One point is not a strip, and the phone refuses a path of one - so Del on the last point of a side track
  // has to end the side track rather than leave the operator with a drawing that will not save.
  let drawing = startSideTrack(createPath([a, b]), 1);
  drawing = add(drawing, spurEnd);
  drawing = backToLine(drawing);
  drawing = hold(drawing, 1);

  const after = remove(drawing, 1);

  assert.equal(after.paths.length, 1, 'no strip left');
  assert.deepEqual(after.paths[0], [a, b]);
  assert.equal(after.active, 0, 'the line is in hand again');
  assert.deepEqual(undo(after).paths[1], [b, spurEnd], 'and one Ctrl+Z brings it back');
});

test('every path is a feature of its own, so the map draws the side tracks too', () => {
  const feature = pathsFeature(createPath([a, b]).paths.concat([[b, spurEnd]]));

  assert.equal(feature.features.length, 2);
  assert.equal(feature.features[0].geometry.coordinates.length, 2);
  assert.equal(feature.features[1].geometry.coordinates[1][1], spurEnd.lat);
});
