/*
 * What the desk's list is showing.
 *
 * `find.mjs` is pure, so node runs the very file the browser does: the rule that decides which rows are
 * on the screen - the typed words, the picked type, or both - is tested here rather than read by eye in
 * `app.js`.
 *
 * The case worth pinning is the one where the two filters disagree: a row whose name matches but whose
 * kind does not, and the other way about. Either mistake is invisible in a screenshot of a list that
 * happens to be short.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { visibleAssets } from '../../main/assets/web/find.mjs';

const row = (id, name, kind) => ({ asset: { id, name, kind } });

const rows = [
  row(1, 'Estuary road', 'ROAD'),
  row(2, 'Estuary buildings', 'BUILDING'),
  row(3, 'Shearing shed', 'BUILDING'),
  row(4, 'Home fenceline', 'FENCELINE')
];

const names = (shown) => shown.map((one) => one.asset.name);

test('nothing typed and Anything picked is the whole work, in the order it arrived', () => {
  assert.deepEqual(names(visibleAssets(rows, '', null)), names(rows));
  assert.deepEqual(visibleAssets(rows, '', null), rows, 'and the same rows, not copies');
});

test('the typed words narrow it, without case, matching anywhere in the name', () => {
  assert.deepEqual(names(visibleAssets(rows, 'estuary', null)), ['Estuary road', 'Estuary buildings']);
  assert.deepEqual(names(visibleAssets(rows, 'SHED', null)), ['Shearing shed']);
  assert.deepEqual(
    names(visibleAssets(rows, '   ', null)),
    names(rows),
    'a box holding only spaces is a box with nothing typed'
  );
  assert.deepEqual(names(visibleAssets(rows, 'paddock', null)), [], 'and a miss shows nothing');
});

test('the picked type narrows it to that type', () => {
  assert.deepEqual(names(visibleAssets(rows, '', 'BUILDING')), ['Estuary buildings', 'Shearing shed']);
  assert.deepEqual(names(visibleAssets(rows, '', 'SIGN')), [], 'a type with nothing in it shows nothing');
});

test('both filters apply, so a name match of another type stays off the list', () => {
  // The one that matters: "Estuary road" matches the words and is not a building.
  assert.deepEqual(names(visibleAssets(rows, 'estuary', 'BUILDING')), ['Estuary buildings']);
  assert.deepEqual(names(visibleAssets(rows, 'fenceline', 'BUILDING')), []);
});

test('nothing to show is an empty list rather than a crash', () => {
  assert.deepEqual(visibleAssets([], '', null), []);
  assert.deepEqual(visibleAssets(undefined, 'anything', 'BUILDING'), []);
});
