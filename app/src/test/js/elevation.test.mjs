/*
 * A line's height, from the desk's side: the words the card says and the shape the chart draws.
 *
 * `elevation.mjs` is pure, so node runs the very file the browser does: the range is pinned to the
 * same words the phone's own card uses, and the chart's arithmetic - the x axis measured along the
 * line, a flat line down the middle, a height missing in the middle - is held here rather than read
 * by eye off a screen.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { elevationChart, elevationWords } from '../../main/assets/web/elevation.mjs';

/** A point on a line running north at a known step, with the height or none. */
const at = (step, altitudeM) => ({ lat: 0.001 * step, lng: 0, altitudeM });

test('the range is said the way the phone says it', () => {
  assert.equal(
    elevationWords({ lowestM: 553.6, highestM: 663.6, climbM: 110.0, descentM: 0.0 }),
    '\u2248554\u2013664 m, up 110 m'
  );
  assert.equal(
    elevationWords({ lowestM: 500.0, highestM: 600.0, climbM: 0.0, descentM: 100.0 }),
    '\u2248500\u2013600 m, down 100 m'
  );
  assert.equal(
    elevationWords({ lowestM: 50.0, highestM: 50.0, climbM: 0.0, descentM: 0.0 }),
    '\u224850\u201350 m',
    'a flat line says nothing about a way it goes'
  );
  assert.equal(elevationWords(null), null, 'no height, no words');
});

test('a chart is the heights drawn, the lowest near the bottom and the highest near the top', () => {
  const chart = elevationChart([at(0, 100), at(1, 200), at(2, 150)], 100, 30, 2);

  assert.deepEqual(chart, {
    line: 'M2 28 L50 2 L98 15',
    area: 'M2 30 L2 28 L50 2 L98 15 L98 30 Z'
  });
});

test('a flat line is drawn down the middle rather than divided by nothing', () => {
  const chart = elevationChart([at(0, 100), at(1, 100)], 100, 30, 2);

  assert.deepEqual(chart, { line: 'M2 15 L98 15', area: 'M2 30 L2 15 L98 15 L98 30 Z' });
});

test('a height missing in the middle is stepped over, and its metres still count', () => {
  const chart = elevationChart([at(0, 100), at(1, null), at(2, 150)], 100, 30, 2);

  // Two heights, so two points on the chart - and the second is at the end of the line, where the
  // point it came from is, not at the missing point's place.
  assert.equal(chart.line, 'M2 28 L98 2');
});

test('fewer than two heights is nothing to draw', () => {
  assert.equal(elevationChart([at(0, 100)], 100, 30), null);
  assert.equal(elevationChart([at(0, null), at(1, null)], 100, 30), null);
  assert.equal(elevationChart([], 100, 30), null);
  assert.equal(elevationChart(undefined, 100, 30), null);
});
