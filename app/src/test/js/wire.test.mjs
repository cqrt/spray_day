/*
 * What a save carries.
 *
 * `wire.mjs` is pure, so node runs the very file the browser does: the rule that decides whether a write
 * carries one path or the whole drawing is tested here rather than read by eye in `app.js`.
 *
 * The rule matters because of what happens when it is wrong. A write carrying one path for a track that
 * has a side track is refused by the phone (rather than silently dropping the spur) - so a page that sent
 * the wrong shape for a track with side tracks could not save that track's line at all, and the operator
 * would be told to reload a page that was already right.
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
  const body = drawingBody([line]);

  assert.deepEqual(body, { points: line });
  assert.equal('paths' in body, false, 'and nothing else, or the phone would be sent two drawings');
});

test('a track with nothing drawn at all sends no vertices', () => {
  assert.deepEqual(drawingBody([]), { points: [] });
});

test('a track with a side track sends its paths, the line first', () => {
  const body = drawingBody([line, spur]);

  assert.equal('points' in body, false, 'a single path here would drop the side track on the phone');
  assert.equal(body.paths.length, 2);
  assert.deepEqual(body.paths[0], line, 'the line goes first, which is what the phone reads as the line');
  assert.deepEqual(body.paths[1], spur);
});

test('a side track the operator has just drawn on the desk travels with the line', () => {
  const drawn = [
    { lat: -41.5, lng: 173.9 },
    { lat: -41.5, lng: 173.92 }
  ];
  const newSpur = [{ lat: -41.5, lng: 173.92 }, { lat: -41.52, lng: 173.92 }];

  const body = drawingBody([drawn, newSpur]);

  assert.deepEqual(body.paths, [drawn, newSpur], 'what the desk drew is what the phone is sent');
});

test('the paths sent are the drawing the page holds, not a copy of it', () => {
  const record = { paths: [line, spur] };
  const paths = [record.paths[0], record.paths[1]];

  const body = drawingBody(paths);

  assert.deepEqual(record.paths, [line, spur], 'the record is untouched');
  assert.deepEqual(body.paths, record.paths);
});
