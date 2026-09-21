/*
 * What a save carries.
 *
 * `wire.mjs` is pure, so node runs the very file the browser does: the rule that decides whether a
 * write carries one path or the whole drawing is tested here rather than read by eye in `app.js`.
 *
 * The rule matters because of what happens when it is wrong. A write carrying one path for a track
 * that has a side track is refused by the phone (rather than silently dropping the spur), and a write
 * carrying the wrong number of paths is refused too - so a page that got this wrong could not save a
 * line at all, and the operator would be told about reloading a page that was already right.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { drawingBody } from '../../main/assets/web/wire.mjs';

const line = [
  { lat: -41.5, lng: 173.9 },
  { lat: -41.5, lng: 173.91 }
];

const spur = [
  { lat: -41.5, lng: 173.91 },
  { lat: -41.51, lng: 173.91 }
];

test('a track with no side tracks sends one path, as every page before this one did', () => {
  const body = drawingBody({ paths: [line] }, line);

  assert.deepEqual(body, { points: line });
  assert.equal('paths' in body, false, 'and nothing else, or the phone would be sent two drawings');
});

test('a track the page was handed no paths for sends one path', () => {
  assert.deepEqual(drawingBody({}, line), { points: line });
  assert.deepEqual(drawingBody(null, line), { points: line });
});

test('a track with a side track sends its paths, the line changed and the spur untouched', () => {
  const moved = [
    { lat: -41.5, lng: 173.9 },
    { lat: -41.5, lng: 173.92 }
  ];

  const body = drawingBody({ paths: [line, spur] }, moved);

  assert.equal(body.paths.length, 2, 'the line and the side track');
  assert.deepEqual(body.paths[0], moved);
  assert.deepEqual(body.paths[1], spur, 'the side track goes back exactly as it came');
  assert.equal('points' in body, false);
});

test('the line goes first, which is what the phone reads as the line', () => {
  const body = drawingBody({ paths: [line, spur] }, line);

  assert.deepEqual(body.paths[0], line);
});

test('the record is not written to: the spur sent back is the one the page holds', () => {
  const record = { paths: [line, spur] };

  const body = drawingBody(record, line);

  assert.deepEqual(record.paths, [line, spur], 'the record is what the next save reads');
  assert.notEqual(body.paths, record.paths, 'a fresh array, so a later edit cannot reach the record');
});
