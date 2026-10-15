/*
 * A line's height, from the desk's side of it.
 *
 * The phone works the range out and hands it over in the record; this is what the page does with it -
 * say the range in the words the phone's own card uses, and turn the heights it already holds into
 * the shape a chart draws. Pure, so node runs the very file the browser does.
 *
 * It keeps its own flat-earth step rather than importing `geometry.mjs`: the page fetches that module
 * with the token on its URL, and a second import without it is a request the phone refuses.
 */

const METRES_PER_DEGREE = 111132;

/**
 * The range in the words the phone's card uses, or null when the record carries no height.
 *
 * The same wording and the same four figures as `ElevationPhrase` on the phone, so the two cards say
 * the same sentence about the same track.
 */
export function elevationWords(elevation) {
  if (!elevation) return null;
  const way = elevation.climbM > 0 && elevation.climbM >= elevation.descentM
    ? `, up ${whole(elevation.climbM)} m`
    : elevation.descentM > 0
      ? `, down ${whole(elevation.descentM)} m`
      : '';
  return `\u2248${whole(elevation.lowestM)}\u2013${whole(elevation.highestM)} m${way}`;
}

/** A whole number of metres: a device's height is not accurate to the centimetre it prints. */
function whole(metres) {
  return String(Math.round(metres));
}

/**
 * A path's heights as the two strings a chart draws, in a box `width` by `height` with `pad` inside
 * it: `line` is the outline and `area` the same filled down to the bottom.
 *
 * Null when fewer than two points carry a height, because a chart of nothing is not a chart. The x
 * axis is measured along the line rather than by point number, so a file sampled unevenly draws as
 * the ground it is; a height missing in the middle is stepped over but its metres still count.
 */
export function elevationChart(points, width, height, pad = 2) {
  const samples = [];
  let along = 0;
  let previous = null;
  for (const point of points ?? []) {
    if (previous) along += metresBetween(previous, point);
    previous = point;
    if (typeof point.altitudeM === 'number') samples.push({ x: along, y: point.altitudeM });
  }
  if (samples.length < 2) return null;

  const first = samples[0];
  const last = samples[samples.length - 1];
  const lowest = Math.min(...samples.map((sample) => sample.y));
  const highest = Math.max(...samples.map((sample) => sample.y));
  const spanAlong = last.x - first.x || 1;
  const spanHeight = highest - lowest;

  const at = (sample) => {
    const x = pad + ((sample.x - first.x) / spanAlong) * (width - pad * 2);
    const y = spanHeight === 0
      ? height / 2
      : pad + (1 - (sample.y - lowest) / spanHeight) * (height - pad * 2);
    return [round(x), round(y)];
  };

  const placed = samples.map(at);
  const line = placed.map(([x, y], index) => `${index === 0 ? 'M' : 'L'}${x} ${y}`).join(' ');
  const area = `M${placed[0][0]} ${height} ` +
    placed.map(([x, y]) => `L${x} ${y}`).join(' ') +
    ` L${placed[placed.length - 1][0]} ${height} Z`;
  return { line, area };
}

function metresBetween(a, b) {
  const midLat = (((a.lat + b.lat) / 2) * Math.PI) / 180;
  const dx = (b.lng - a.lng) * METRES_PER_DEGREE * Math.cos(midLat);
  const dy = (b.lat - a.lat) * METRES_PER_DEGREE;
  return Math.hypot(dx, dy);
}

function round(value) {
  return Math.round(value * 100) / 100;
}
