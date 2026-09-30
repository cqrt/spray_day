/*
 * What the whole farm comes to, in the corner box.
 *
 * `stats.mjs` is pure, so node runs the very file the browser does: the counts, the totals and the
 * words for them are pinned here rather than read by eye in a screenshot. The arithmetic itself is
 * the phone's own, so the cases worth pinning are the ones where a second opinion could differ: a
 * never-sprayed asset counted with the overdue ones, a ring's measured ground beside a line's
 * estimate, both passes of a two-pass line, and an area that covers only some of the assets.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { areaText, farmStats, farmStatsLines, metresText } from '../../main/assets/web/stats.mjs';

/** A track: so many metres of line, sprayed over a swath width somebody has said. */
const line = (id, dueStatus, lengthM, swathWidthM = 3, passesRequired = 1) => ({
  dueStatus,
  asset: { id, shape: 'LINE', lengthM, swathWidthM, passesRequired }
});

/** A carpark: the metres round its boundary, and the ground its corners enclose. */
const carpark = (id, dueStatus, areaM2 = 3_500) => ({
  dueStatus,
  asset: { id, shape: 'AREA', lengthM: 260, areaM2, swathWidthM: null, passesRequired: 1 }
});

/** A trough: a point, with no length and no ground to measure. */
const spot = (id, dueStatus) => ({
  dueStatus,
  asset: { id, shape: 'POINT', lengthM: 0, swathWidthM: null, passesRequired: 1 }
});

test('the counts group a never-sprayed asset with the overdue ones, as the phone does', () => {
  const stats = farmStats([
    line(1, 'OVERDUE', 500),
    line(2, 'NEVER_SPRAYED', 500),
    line(3, 'DUE_SOON', 500),
    line(4, 'NOT_DUE', 500)
  ]);

  assert.equal(stats.count, 4);
  assert.equal(stats.overdue, 2, 'both need going over, and the map paints them the same colour');
  assert.equal(stats.dueSoon, 1);
  assert.equal(stats.notDue, 1);
});

test('the length is every line added up, and a place adds nothing to it', () => {
  const stats = farmStats([line(1, 'NOT_DUE', 1500), line(2, 'NOT_DUE', 850), spot(3, 'NOT_DUE')]);

  assert.equal(stats.lengthM, 2350);
});

test("a ring's own ground and a line's estimate are both counted, and never read as each other", () => {
  const stats = farmStats([carpark(1, 'NOT_DUE', 3_500), line(2, 'NOT_DUE', 1000)]);

  assert.equal(stats.areaSqm, 6_500, '3,500 measured from the corners and 3,000 estimated from a width');
  assert.equal(stats.areaAssetCount, 2, 'both of them know an area, so the figure claims to cover both');
});

test('both passes of a two-pass line are ground, not the same ground twice', () => {
  const stats = farmStats([line(1, 'NOT_DUE', 1000, 3, 2)]);

  assert.equal(stats.areaSqm, 6_000);
});

test('a line with no width said cannot be estimated, and claims no area at all', () => {
  const stats = farmStats([line(1, 'NOT_DUE', 800, null)]);

  assert.equal(stats.areaSqm, 0);
  assert.equal(stats.areaAssetCount, 0);
});

test('the lines say how long the farm is and how much ground it covers', () => {
  const lines = farmStatsLines(farmStats([line(1, 'OVERDUE', 1500), line(2, 'NOT_DUE', 850)]));

  assert.deepEqual(lines, ['Total length 2.35 km', 'Total area 7050 m²']);
});

test('an area covering only some of the assets is still said plainly', () => {
  const lines = farmStatsLines(farmStats([line(1, 'NOT_DUE', 1000), spot(2, 'NOT_DUE')]));

  assert.deepEqual(lines, ['Total length 1.00 km', 'Total area 3000 m²']);
});

test('a farm of places says no figures at all rather than zeros', () => {
  // A zero beside a real figure is the kind of number that gets added up and believed, and a farm
  // of troughs has neither a length nor an area - nothing is invented for it.
  assert.deepEqual(farmStatsLines(farmStats([spot(1, 'NOT_DUE')])), []);
});

test('how much of the farm is left is not a figure the box says - the counts have said it', () => {
  // Three assets' worth of work outstanding, and the box still says the farm's size and nothing about
  // what is left of it: a third line here is a fifth of the map repeating the counts above it.
  const lines = farmStatsLines(farmStats([
    line(1, 'OVERDUE', 2500),
    line(2, 'DUE_SOON', 800),
    line(3, 'NEVER_SPRAYED', 1000)
  ]));

  assert.deepEqual(lines, ['Total length 4.30 km', 'Total area 1.3 ha']);
});

test('figures are said the way the phone says them', () => {
  assert.equal(metresText(850), '850 m');
  assert.equal(metresText(2350), '2.35 km');
  assert.equal(areaText(420), '420 m²');
  assert.equal(areaText(10_000), '1.0 ha');
  assert.equal(areaText(25_000), '2.5 ha');
  // A zero is not a measurement, so the box's rules never say one of these: nothing is said at all.
  assert.equal(areaText(0), '');
});

test('nothing to say it about is an empty answer rather than a crash', () => {
  assert.equal(farmStats(null).count, 0);
  assert.deepEqual(farmStatsLines(farmStats(undefined)), []);
});
