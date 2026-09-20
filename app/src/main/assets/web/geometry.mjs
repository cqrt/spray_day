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

/** A line being drawn: its vertices, and the two stacks either side of where it is now. */
export function createPath(points = []) {
  return { points: points.map(vertex), past: [], future: [] };
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

/** One step: the line as it was, on both stacks at once. */
function stepped(state, points) {
  return {
    points: points.map(vertex),
    past: [...state.past, state.points].slice(-HISTORY_LIMIT),
    // Anything new is a new history: what had been undone is not the future any more.
    future: []
  };
}

export function canUndo(state) {
  return state.past.length > 0;
}

export function canRedo(state) {
  return state.future.length > 0;
}

export function undo(state) {
  if (!canUndo(state)) return state;
  return {
    points: state.past[state.past.length - 1],
    past: state.past.slice(0, -1),
    future: [state.points, ...state.future]
  };
}

export function redo(state) {
  if (!canRedo(state)) return state;
  return {
    points: state.future[0],
    past: [...state.past, state.points],
    future: state.future.slice(1)
  };
}

/** Adds a vertex to the end: what clicking the map does while a line is being drawn. */
export function add(state, point) {
  return stepped(state, [...state.points, point]);
}

/** Moves a vertex: what letting go of a dragged handle does. */
export function move(state, index, point) {
  if (index < 0 || index >= state.points.length) return state;
  const points = state.points.slice();
  points[index] = vertex(point);
  // A drag that ends where it began is a click that went nowhere rather than a step, and a history
  // full of those would have the operator pressing Ctrl+Z twice to undo one thing.
  if (samePlace(points[index], state.points[index])) return state;
  return stepped(state, points);
}

/** Removes a vertex: what the delete key does to the one the operator last touched. */
export function remove(state, index) {
  if (index < 0 || index >= state.points.length) return state;
  const points = state.points.slice();
  points.splice(index, 1);
  return stepped(state, points);
}

/** Puts a vertex where the line was clicked, between the two it was clicked between. */
export function insert(state, index, point) {
  const points = state.points.slice();
  points.splice(index, 0, vertex(point));
  return stepped(state, points);
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

/** The line as the map's own source wants it, or nothing at all when there is nothing drawn yet. */
export function toFeature(points) {
  const coordinates = points.map((point) => [point.lng, point.lat]);
  return {
    type: 'FeatureCollection',
    features:
      points.length === 0
        ? []
        : [
            {
              type: 'Feature',
              properties: {},
              geometry:
                points.length === 1
                  ? { type: 'Point', coordinates: coordinates[0] }
                  : { type: 'LineString', coordinates }
            }
          ]
  };
}

/** Every vertex of a feature: how the other assets' places to snap onto are gathered. */
export function verticesOf(feature) {
  const geometry = feature && feature.geometry;
  if (!geometry) return [];
  if (geometry.type === 'Point') {
    return [vertex({ lat: geometry.coordinates[1], lng: geometry.coordinates[0] })];
  }
  if (geometry.type !== 'LineString') return [];
  return geometry.coordinates.map((pair) => vertex({ lat: pair[1], lng: pair[0] }));
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
