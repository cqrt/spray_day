/*
 * The line the desk is drawing.
 *
 * Plain functions over a plain object, and no DOM and no map anywhere in it: what needs either - where
 * a click landed, what is under the cursor - arrives as an argument, and what is left is arithmetic.
 * That is what makes this file runnable by node, so Ctrl+Z on a laptop can be tested without a browser
 * (`app/src/test/js/geometry.test.mjs`, which CI runs).
 *
 * **The history is snapshots of the line, not a log of operations.** A drawn track is tens of points,
 * so a snapshot is a few kilobytes, and restoring the previous array cannot get an inverse slightly
 * wrong - the only way it can be wrong is if the snapshot was, which a test sees at once.
 *
 * **The phone is never asked to undo.** Nothing in here talks to the phone: the line is sent when the
 * operator is finished with it, so an hour of tidying a track is one write rather than fifty - and each
 * of those writes is a whole path either way, which is what `WebEditorEdit`'s `points` means on the
 * other end of it.
 */

/** How far back the desk can go. Past this the oldest step is forgotten. */
export const HISTORY_LIMIT = 100;

/** How close, in pixels, a click has to be to a handle to mean it. */
export const HANDLE_PX = 10;

/** How close, in pixels, the cursor has to be to something to snap onto it. */
export const SNAP_PX = 12;

/**
 * How many corners make a ring, which is the phone's own least for one.
 *
 * `Ring.MIN_CORNERS`. Under three there is no ground inside, which is what makes two corners a line
 * and not a fenced-off thing - the phone refuses the save with its own sentence, and this is the
 * drawing saying the same before it is asked.
 */
export const RING_CORNERS = 3;

/**
 * A drawing of the paths given: path 0 the line, the rest its side tracks.
 *
 * Paths with nothing in them are dropped rather than kept: an empty path is not a path, and it would be
 * a row of nothing on the phone.
 */
export function createPaths(paths = []) {
  return {
    paths: paths.map((path) => path.map(vertex)).filter((path) => path.length > 0),
    active: 0,
    past: [],
    future: []
  };
}

/** A drawing that is one line and nothing else: a brand-new track, and the single-path tests here. */
export function createPath(points = []) {
  return createPaths([points]);
}

/** The line itself: where a track runs, and what a side track hangs off. */
export function line(state) {
  return state.paths[0] ?? [];
}

/** The side tracks, in the order they were drawn. */
export function sideTrackCount(state) {
  return Math.max(0, state.paths.length - 1);
}

/** The path the next click goes to: the line, or the side track being drawn. */
export function activePath(state) {
  return state.paths[state.active] ?? [];
}

/**
 * True while a **side track** is the path in hand.
 *
 * Called "in hand" rather than "being drawn" because the two are not the same: a side track taken hold of by
 * clicking it is the path the handles, the delete key and the drags belong to, and one that has just been
 * started is that *and* unfinished. Everything the page offers for a side track - the way back to the line,
 * removing it, the words in the bar - hangs off this one question.
 */
export function sideTrackInHand(state) {
  return state.active > 0;
}

/**
 * Puts a path in hand, without touching the drawing.
 *
 * Not a step of the history, deliberately: taking hold of a side track changes nothing about the track, and
 * an operator who has just dragged a corner wants Ctrl+Z to take *that* back rather than a click that
 * selected something. The drawing's undo stack is snapshots of `{paths, active}`, so a selection followed by
 * an edit is still one step, and undoing it hands back the path that was in hand when the edit was made.
 */
export function hold(state, index) {
  if (index < 0 || index >= state.paths.length || index === state.active) return state;
  return { ...state, active: index };
}

/**
 * A vertex copied down to the two numbers the phone stores.
 *
 * So nothing a map event carries - a screen position, an altitude, a `z` - can reach the wire, and so
 * comparing two vertices compares only the things that matter.
 */
export function vertex(point) {
  return { lat: point.lat, lng: point.lng };
}

/** Whether two vertices are the same spot to the bit, which is how the phone reads a repeat too. */
export function samePlace(a, b) {
  return a.lat === b.lat && a.lng === b.lng;
}

/** One step: the drawing as it was, on both stacks at once. */
function stepped(state, paths, active = state.active) {
  const kept = paths.filter((path) => path.length > 0);
  return {
    paths: kept.map((path) => path.map(vertex)),
    // The path being worked on has to exist: a step that takes the active one away hands the line back.
    active: Math.min(Math.max(active, 0), Math.max(0, kept.length - 1)),
    past: [...state.past, { paths: state.paths, active: state.active }].slice(-HISTORY_LIMIT),
    // Anything new is a new history: what had been undone is not the future any more.
    future: []
  };
}

/** The same paths with the active one replaced, which is what every edit of one path is. */
function withActive(state, path) {
  return stepped(
    state,
    state.paths.map((other, index) => (index === state.active ? path : other))
  );
}

export function canUndo(state) {
  return state.past.length > 0;
}

export function canRedo(state) {
  return state.future.length > 0;
}

export function undo(state) {
  if (!canUndo(state)) return state;
  const previous = state.past[state.past.length - 1];
  return {
    paths: previous.paths,
    active: Math.min(previous.active, Math.max(0, previous.paths.length - 1)),
    past: state.past.slice(0, -1),
    future: [{ paths: state.paths, active: state.active }, ...state.future]
  };
}

export function redo(state) {
  if (!canRedo(state)) return state;
  const next = state.future[0];
  return {
    paths: next.paths,
    active: Math.min(next.active, Math.max(0, next.paths.length - 1)),
    past: [...state.past, { paths: state.paths, active: state.active }],
    future: state.future.slice(1)
  };
}

/** Adds a vertex to the end of the path being worked on: what clicking the map does. */
export function add(state, point) {
  if (state.paths.length === 0) return stepped(state, [[point]], 0);
  return withActive(state, [...activePath(state), point]);
}

/**
 * Moves a vertex of the path being worked on: what letting go of a dragged handle does.
 *
 * Dragging a **line** vertex takes any junction on it along: a side track's first vertex is that very
 * vertex, so the two paths stay met exactly while the corner they meet at is moved. The alternative is a
 * drag that quietly leaves the side track hanging off nothing, which is a drawing the phone refuses - and
 * refusing would be no good here, because an operator moving a corner has done nothing wrong.
 */
export function move(state, index, point) {
  const path = activePath(state);
  if (index < 0 || index >= path.length) return state;
  const moved = path.slice();
  moved[index] = vertex(point);
  // A drag that ends where it began is a click that went nowhere rather than a step, and a history
  // full of those would have the operator pressing Ctrl+Z twice to undo one thing.
  if (samePlace(path[index], moved[index])) return state;

  // The first point of a side track **is** a vertex of the line - that is what a junction is - so dragging
  // it is dragging that line vertex, which is the only way to take a junction somewhere else without letting
  // go of the side track first. The line's own move takes every side track hanging off it with it, this one
  // included, and the side track goes back into the operator's hand afterwards.
  if (state.active > 0 && index === 0) {
    const onTheLine = line(state).findIndex((one) => samePlace(one, path[0]));
    if (onTheLine >= 0) {
      return hold(move(hold(state, 0), onTheLine, point), state.active);
    }
  }

  const was = path[index];
  const paths = state.paths.map((other, pathIndex) => {
    if (pathIndex === state.active) return moved;
    if (state.active !== 0 || !startsAt(other, was)) return other;
    return [moved[index], ...other.slice(1)];
  });
  return stepped(state, paths, state.active);
}

/**
 * Removes a vertex of the path being worked on: what the delete key does to the one last touched.
 *
 * A side track hangs off **a vertex of the line** - its own first vertex is that vertex - so taking that
 * vertex off the line takes the side track with it: what is left would be a strip that starts nowhere,
 * and the phone refuses those. Taking the whole strip is the visible half of the change, and one Ctrl+Z
 * brings both back. The same rule from the other side: taking the junction off a side track takes the
 * side track, since a strip with no start is not a strip.
 */
export function remove(state, index) {
  const path = activePath(state);
  if (index < 0 || index >= path.length) return state;
  const gone = path[index];
  const kept = path.filter((_, at) => at !== index);
  // A side track left with fewer than two points is not a side track any more: one point is not a strip, and
  // the phone refuses a path of one, so it comes off whole - the same rule `backToLine` applies. Taking the
  // junction off a side track ends it the same way, because a strip with no start is not a strip either.
  const inHand = sideTrackInHand(state);
  const noLongerASideTrack = inHand && (index === 0 || kept.length < 2);
  const paths = state.paths.map((other, pathIndex) => {
    if (pathIndex !== state.active) {
      if (inHand || !startsAt(other, gone)) return other;
      return [];
    }
    return noLongerASideTrack ? [] : kept;
  });
  return stepped(state, paths, noLongerASideTrack ? 0 : state.active);
}

/** Whether a path starts at [point], to the bit: how "this side track hangs off that vertex" is read. */
function startsAt(path, point) {
  return path.length > 0 && samePlace(path[0], point);
}

/** Puts a vertex where the path being worked on was clicked, between the two it was clicked between. */
export function insert(state, index, point) {
  const path = activePath(state).slice();
  path.splice(index, 0, vertex(point));
  return withActive(state, path);
}

/* ---- Side tracks: a line with strips hanging off it ---------------------------------- */

/**
 * Starts a side track on the line, from the vertex given or from where the track ends.
 *
 * The first vertex of the side track **is** that line vertex - the same two numbers, not a copy - which is
 * what the phone's own drawing does and what its rules ask for: the two paths meet at a vertex, rather than
 * being two lines that happen to be near each other.
 *
 * [junctionIndex] is the vertex the operator picked out on the line, and the fallback is the track's own
 * end. The fallback matters twice: drawing a track from scratch has no choice to offer, and a picked vertex
 * can stop existing (Undo, a delete) between picking it and using it - in which case hanging the side track
 * off the end is the only honest answer, rather than a path that starts nowhere.
 */
export function startSideTrack(state, junctionIndex = null) {
  const linePoints = line(state);
  if (sideTrackInHand(state) || linePoints.length < 2) return state;
  const onTheLine =
    junctionIndex !== null && junctionIndex >= 0 && junctionIndex < linePoints.length;
  const at = onTheLine ? junctionIndex : linePoints.length - 1;
  return stepped(state, [...state.paths, [linePoints[at]]], state.paths.length);
}

/**
 * The junction as a feature of its own, or null when the side track would leave the track's end.
 *
 * The map has to be able to show which vertex a side track is about to hang off, because otherwise a
 * picked one is invisible and the operator has no way to see that the click took. It is a Point with a
 * property rather than a path, so the drawing's own layers can pick it out, and `[lng, lat]` - which is the
 * one thing in this file that is not the map's problem to remember.
 */
export function junctionFeature(point) {
  if (!point) return null;
  return {
    type: 'Feature',
    properties: { junction: true },
    geometry: { type: 'Point', coordinates: [point.lng, point.lat] }
  };
}

/**
 * Back to the line.
 *
 * A side track that never got a second point is taken off rather than kept: it is one click with nothing
 * on the map, and leaving it there would be a path the phone refuses to store and the operator cannot see.
 */
export function backToLine(state) {
  if (!sideTrackInHand(state)) return state;
  return stepped(state, state.paths.filter((path, index) => index === 0 || path.length >= 2), 0);
}

/**
 * Takes the side track being drawn off, whatever is on it.
 *
 * The way to have second thoughts about one that is already two points long. A side track that is *not*
 * the one being drawn is left alone: this is about the path under the operator's hand, which is the only
 * one the map can point at.
 */
export function dropSideTrack(state) {
  if (!sideTrackInHand(state)) return state;
  return stepped(state, state.paths.filter((_, index) => index !== state.active), 0);
}

/**
 * The metres the whole track covers: every path's own length, each counted once.
 *
 * The rule the phone's own arithmetic keeps, and the number that made the old up-and-back way of drawing
 * a spur wrong: walk up it and back down it inside the line and its metres arrive twice, while a 500 m
 * line with a 50 m side track is 550 m of ground however it is driven.
 */
export function lengthMeters(paths) {
  let total = 0;
  for (const path of paths) {
    for (let index = 1; index < path.length; index++) {
      total += metresBetween(path[index - 1], path[index]);
    }
  }
  return total;
}

/**
 * Metres between two vertices, on the flat-earth approximation this file uses everywhere else.
 *
 * Over a fence it is exact to a metre, and over a whole farm the error is a few parts in ten thousand -
 * which matters for a number nobody navigates by and not at all for the shape of anything.
 */
function metresBetween(a, b) {
  const midLat = (((a.lat + b.lat) / 2) * Math.PI) / 180;
  const dx = (b.lng - a.lng) * METRES_PER_DEGREE * Math.cos(midLat);
  const dy = (b.lat - a.lat) * METRES_PER_DEGREE;
  return Math.hypot(dx, dy);
}

/**
 * A ring's corners: the closing vertex taken off, when the last one repeats the first.
 *
 * The phone stores a ring **closed** - the last vertex is the first corner again - so that everything
 * that reads the geometry afterwards gets the closing side without asking what shape it is holding,
 * and so a shape can be read as a ring by looking at it alone. The desk's drawing is not one of those
 * readers: a handle on the repeated corner is a point that can be dragged off on its own, which would
 * turn one boundary into a boundary and a stray line, so a ring opened for editing is opened on its
 * corners. The closing side comes back from the shape's own rule ([ring]) and, from there, from the
 * phone, which closes what it is given whether it arrived open or closed.
 *
 * The comparison is exact, as it is everywhere in this file: the desk snaps a vertex onto another by
 * copying its coordinates, so a corner that is meant to be the same place *is* the same two numbers.
 */
export function corners(points) {
  if (points.length >= 2 && samePlace(points[0], points[points.length - 1])) {
    return points.slice(0, -1);
  }
  return points;
}

/**
 * A ring as it is drawn: the sides that have been laid, and the closing side.
 *
 * Nothing closes until there are [RING_CORNERS] corners, because nothing is enclosed until then: two
 * points drawn back to each other is the same side twice, and showing it would be the page claiming a
 * boundary the phone is about to refuse. This is the one place the page adds a side the operator did
 * not click - the same side the phone adds when it stores the ring.
 */
export function ring(points) {
  const around = corners(points);
  if (around.length < RING_CORNERS) return around;
  return [...around, around[0]];
}

/**
 * The ground a ring encloses, in square metres - the phone's own arithmetic, to the metre.
 *
 * The shoelace formula on the flat taken at the ring's own mean latitude, which is what
 * `Ring.areaSqm` does and for the same reason: a carpark is tens of metres across, where a degree of
 * longitude is the same length at both ends to within a hand's width. It is worked out from the
 * **corners** rather than from what is drawn, so a ring opened for editing measures the same before
 * and after its closing side is put back - and zero for fewer than three corners, which is the honest
 * answer for a path and the number the drawing shows until there is a ring.
 */
export function areaSqm(points) {
  const around = corners(points);
  if (around.length < RING_CORNERS) return 0;

  const meanLat = around.reduce((total, point) => total + point.lat, 0) / around.length;
  const metresPerDegLng =
    METRES_PER_DEG_LNG_AT_EQUATOR * Math.cos((meanLat * Math.PI) / 180);

  let twiceTheArea = 0;
  for (let index = 0; index < around.length; index++) {
    const here = around[index];
    const next = around[(index + 1) % around.length];
    twiceTheArea +=
      here.lng * metresPerDegLng * (next.lat * METRES_PER_DEG_LAT) -
      next.lng * metresPerDegLng * (here.lat * METRES_PER_DEG_LAT);
  }
  return Math.abs(twiceTheArea) / 2;
}

/**
 * The metres round a ring: its sides and the closing side.
 *
 * What the phone's own "Round it" measures, and what the box in the map's corner counts while a
 * boundary is being drawn. The corners alone would be one side short, which is the kind of figure
 * nobody would notice until a rate per metre was worked out from it. It walks [ring] rather than the
 * corners so the figure is the length of the line the map is drawing: below three corners there is no
 * closing side to count, because there is nothing enclosed to close.
 */
export function perimeterMeters(points) {
  const drawn = ring(points);
  if (drawn.length < 2) return 0;
  return lengthMeters([drawn]);
}

/**
 * Which vertex is under the cursor, or -1.
 *
 * Measured in screen pixels rather than degrees: a degree of longitude is 111 km at the equator and
 * 70 km down here, so "point one" is not a size, while ten pixels under a mouse is a handle. `project`
 * turns a vertex into `{x, y}` - MapLibre's own `map.project` at the call site.
 */
export function vertexAt(points, cursor, project, tolerancePx = HANDLE_PX) {
  let best = -1;
  let bestDistance = tolerancePx;
  points.forEach((point, index) => {
    const distance = distanceBetween(project(point), cursor);
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = index;
    }
  });
  return best;
}

/**
 * The vertex the cursor should snap onto, or null when it is not near one.
 *
 * The candidates are the caller's: another asset's vertices, and - while a line is being drawn - the
 * line's own first vertex, so a track can be closed back onto where it started. What comes back is a
 * copy of the candidate, so a snapped vertex is *exactly* the vertex it snapped to: that is what makes
 * "these two are the same place" true to the bit on the phone as well as here, which is how the phone
 * reads a repeated vertex when a line is tidied.
 */
export function snap(point, candidates, project, tolerancePx = SNAP_PX) {
  const cursor = project(point);
  let best = null;
  let bestDistance = tolerancePx;
  for (const candidate of candidates) {
    const distance = distanceBetween(project(candidate), cursor);
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = candidate;
    }
  }
  return best === null ? null : vertex(best);
}

/**
 * Where a click on the line goes: the index a new vertex takes, or -1 when the click missed it.
 *
 * The distance is to the segment as drawn, in screen pixels, because a line one pixel wide is clicked
 * in one-pixel terms. The index returned is where the new vertex goes, which is after the segment it
 * was clicked on - so the caller inserts and does not have to work out what "after" meant.
 */
export function segmentAt(points, cursor, project, tolerancePx = HANDLE_PX) {
  if (points.length < 2) return -1;
  let best = -1;
  let bestDistance = tolerancePx;
  for (let index = 0; index + 1 < points.length; index++) {
    const distance = distanceToSegment(cursor, project(points[index]), project(points[index + 1]));
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = index + 1;
    }
  }
  return best;
}

/**
 * The path as the map's own source wants it, or nothing at all when there is nothing drawn yet.
 *
 * `closed` is for a shape that is ground: the first corner is repeated at the end, which is the closing
 * side drawn as a line like every other side, and it is the same corner list the phone stores. Left
 * false, a ring being drawn would show its sides with a gap where the ground's edge comes back to where
 * it started - and the gap would close itself, unannounced, at the moment of saving.
 */
export function toFeature(points, closed = false) {
  const drawn = closed ? ring(points) : points;
  const coordinates = drawn.map((point) => [point.lng, point.lat]);
  return {
    type: 'FeatureCollection',
    features:
      drawn.length === 0
        ? []
        : [
            {
              type: 'Feature',
              properties: {},
              geometry:
                drawn.length === 1
                  ? { type: 'Point', coordinates: coordinates[0] }
                  : { type: 'LineString', coordinates }
            }
          ]
  };
}

/**
 * Every path as one FeatureCollection: the line and its side tracks, in one source.
 *
 * `closed` belongs to the **first** path, which is the line - or, for a kind that is ground, the
 * boundary itself. A side track is a strip off a line and a ring has none (`AssetPathEdits` refuses
 * them), so there is no second path to close.
 */
export function pathsFeature(paths, closed = false) {
  return {
    type: 'FeatureCollection',
    features: paths
      .map((path, index) => toFeature(path, closed && index === 0).features[0])
      .filter(Boolean)
  };
}

/**
 * The path a click landed on, the one in hand aside, or -1.
 *
 * A click that lands on a path the operator is **not** holding takes hold of it rather than changing it, and
 * this is how a side track's own points are reached at all: only the path in hand offers handles, so without
 * this there is no gesture that makes a side track the path in hand. The path in hand is left out on purpose
 * - a click on *it* means "put a point in here", which the caller asks `segmentAt` about first.
 *
 * The distance is to the path as drawn, in screen pixels, at the same tolerance a click on the path in hand
 * is measured with, so the two rules feel like one.
 */
export function otherPathAt(paths, active, cursor, project, tolerancePx = HANDLE_PX) {
  let best = -1;
  let bestDistance = tolerancePx;
  paths.forEach((path, index) => {
    if (index === active || path.length === 0) return;
    let distance = Infinity;
    if (path.length === 1) {
      distance = distanceBetween(project(path[0]), cursor);
    } else {
      for (let at = 0; at + 1 < path.length; at++) {
        distance = Math.min(distance, distanceToSegment(cursor, project(path[at]), project(path[at + 1])));
      }
    }
    if (distance <= bestDistance) {
      bestDistance = distance;
      best = index;
    }
  });
  return best;
}

/**
 * Every vertex of a feature, whatever its geometry nests.
 *
 * Two things read this: the corners of the other assets to snap onto, and the box the camera is fitted
 * to when an asset is opened from the list. A ring is a Polygon's, so its coordinates sit a level
 * deeper than a line's - and read off the top as pairs they are `[NaN, NaN]`, which MapLibre refuses
 * outright (`flyToAsset` threw on every carpark picked from the list, and a Polygon was invisible to
 * snapping). So the nesting is followed rather than assumed, and one walk does for all five shapes.
 */
export function verticesOf(feature) {
  const geometry = feature && feature.geometry;
  if (!geometry) return [];
  const positions = [];
  const walk = (node) => {
    if (!Array.isArray(node)) return;
    if (typeof node[0] === 'number') {
      positions.push(node);
      return;
    }
    node.forEach(walk);
  };
  // A point's coordinates are one position and every other shape's are lists of them, so the point is
  // walked as a shape that holds it - which is what lets one walk cover all five.
  walk([geometry.coordinates]);
  return positions.map((pair) => vertex({ lat: pair[1], lng: pair[0] }));
}

/* ---- Tracing: following the fence with the button held down ------------------------- */

/**
 * How far, in pixels, the pointer has to travel before a traced point is worth keeping.
 *
 * Four pixels is about what a hand wobbles: sample any finer and a traced fence arrives as a few
 * hundred vertices that are noise, because the *hand* is the noise. What the pointer does between two
 * samples is what [simplify] throws away, so the two constants are two halves of one decision - sample
 * often enough to keep the shape, then keep only the points that made the shape.
 */
export const TRACE_PX = 4;

/**
 * Metres on the ground in one screen pixel, at a latitude and a zoom - the web-mercator formula the
 * maps themselves are drawn with.
 *
 * This is what turns a screen measurement into a ground one, and it is the only place the page's own
 * drawing has anything to say about the size of the earth: a traced line is simplified to what the
 * operator could *see* while tracing, and what they could see is measured in pixels, so the tolerance
 * has to be worked out from the view they traced in. Ported rather than imported because the phone's
 * own maps use the same projection, so the two agree about what a pixel is worth.
 */
export function metresPerPixel(latitude, zoom) {
  const EARTH_CIRCUMFERENCE_M = 40075016.686;
  return (EARTH_CIRCUMFERENCE_M * Math.cos((latitude * Math.PI) / 180)) / Math.pow(2, zoom + 8);
}

/**
 * A traced stroke with the pointer's new position on the end, or the stroke unchanged.
 *
 * What a hand dragging a mouse along a fence produces: a stream of positions, most of which are the
 * same place as the last one to within the width of a pixel. Two things are refused here, and each of
 * them is a way a trace would otherwise arrive as junk: a position that is the same place as the last
 * one **to the bit**, because the phone drops consecutive repeats and there is no point sending it
 * one; and a position within [tolerancePx] of the last one, because that is the hand rather than the
 * fence. Nothing else is refused: snapping is the caller's, because snapping is what the *map* knows.
 */
export function trace(stroke, point, project, tolerancePx = TRACE_PX) {
  const last = stroke[stroke.length - 1];
  if (!last) return [vertex(point)];
  if (samePlace(last, point)) return stroke;
  const moved = distanceBetween(project(last), project(point));
  return moved < tolerancePx ? stroke : [...stroke, vertex(point)];
}

/**
 * The stroke as the line's own vertices: every point that carries shape, and none that does not.
 *
 * Ramer-Douglas-Peucker, which is the right idea here rather than a moving average: it keeps the
 * vertices that are *far from the straight line between their neighbours*, so a corner survives at full
 * sharpness and the wobble along a straight run disappears - including the position the run ends at.
 * A smoothing filter would instead round the corner off, and a fenced corner is exactly what the
 * operator was tracing.
 *
 * [toleranceM] is in metres on the ground - see [metresPerPixel] for where a screen measurement turns
 * into one. Distances inside the search are measured on the plane the line is nearly flat in: over a
 * traced fence, which is hundreds of metres, the curvature of the earth is far below the tolerance,
 * and this is a decision about shape rather than a measurement anybody reads.
 */
export function simplify(points, toleranceM) {
  if (points.length < 3) return points.map(vertex);

  const origin = points[0];
  const latRad = (origin.lat * Math.PI) / 180;
  const toPlane = (point) => ({
    x: (point.lng - origin.lng) * METRES_PER_DEGREE * Math.cos(latRad),
    y: (point.lat - origin.lat) * METRES_PER_DEGREE
  });

  const keep = new Array(points.length).fill(false);
  keep[0] = true;
  keep[points.length - 1] = true;

  // An explicit stack rather than recursion: a five-hundred-point stroke is a fence traced at speed,
  // and what nests is the number of bends worth keeping, not the number of samples.
  const pending = [[0, points.length - 1]];
  while (pending.length > 0) {
    const [first, last] = pending.pop();
    let worst = 0;
    let worstAt = -1;
    for (let index = first + 1; index < last; index++) {
      const away = distanceToSegment(toPlane(points[index]), toPlane(points[first]), toPlane(points[last]));
      if (away > worst) {
        worst = away;
        worstAt = index;
      }
    }
    if (worst > toleranceM && worstAt > 0) {
      keep[worstAt] = true;
      pending.push([first, worstAt], [worstAt, last]);
    }
  }

  return points.filter((_, index) => keep[index]).map(vertex);
}

/** Metres in a degree of latitude: the same mean the phone's own arithmetic uses, to a metre. */
const METRES_PER_DEGREE = 111_320;

/**
 * The phone's own pair, for the one figure that has to come out equal to the phone's.
 *
 * `METRES_PER_DEGREE` above is one number used for both directions, which is what the page's own
 * metres have always been measured with and is right to a metre over a fence. A ring's ground is not
 * that: it is the number the phone stores, the card shows and a spray carries into the handover, and a
 * desk that previewed a different one would be showing a figure the phone replaces the moment it is
 * asked to save. So the area uses the phone's two - `GeoUtils.METRES_PER_DEG_LAT` and
 * `METRES_PER_DEG_LNG_AT_EQUATOR`, the same as `Ring.areaSqm`.
 */
const METRES_PER_DEG_LAT = 111_132;
const METRES_PER_DEG_LNG_AT_EQUATOR = 111_320;

/**
 * The path being worked on with a whole traced stroke on the end - as **one** step of the history.
 *
 * This is the difference between tracing and clicking, and it is the reason a stroke is gathered up
 * before it is put on the line: an operator who has just followed a fence for a hundred metres wants
 * Ctrl+Z to take the fence back, not to walk back along it one sample at a time. A stroke of **one**
 * point is a press rather than a trace, so it is not a step at all - the click the browser sends after
 * it is what adds a vertex, which is what a click has always meant here.
 *
 * The simplification runs over the path's own last vertex and the stroke together, so the run up to the
 * first traced point is measured against that vertex rather than being cut off at the press - which is
 * what makes tracing a side track work: its first vertex is the junction, and tracing from there is a
 * strip following the fence rather than a jump to where the pointer happened to be pressed.
 */
export function traced(state, stroke, toleranceM) {
  if (stroke.length < 2) return state;
  const path = activePath(state);
  const anchor = path[path.length - 1];
  if (!anchor) return withActive(state, simplify(stroke, toleranceM));
  const kept = simplify([anchor, ...stroke], toleranceM);
  return withActive(state, [...path.slice(0, -1), ...kept]);
}

function distanceBetween(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/** The distance from a screen point to a screen segment, measured to the segment and not its ends. */
function distanceToSegment(point, from, to) {
  const spanX = to.x - from.x;
  const spanY = to.y - from.y;
  const lengthSquared = spanX * spanX + spanY * spanY;
  // Two vertices at the same place: the segment is a dot, and the distance is to that dot.
  if (lengthSquared === 0) return distanceBetween(point, from);
  const along = ((point.x - from.x) * spanX + (point.y - from.y) * spanY) / lengthSquared;
  const clamped = Math.max(0, Math.min(1, along));
  return distanceBetween(point, { x: from.x + clamped * spanX, y: from.y + clamped * spanY });
}
