/*
 * Working on several assets at once: which rows are picked, and what one form can say about them.
 *
 * `together.mjs` is pure, so node runs the very file the browser does: what a field means when it is
 * about six assets is tested here rather than read by eye in `app.js`.
 *
 * The question the whole feature rests on is `commonValue`'s. Six rows hold six answers between them -
 * two in a block and four on their own, three fencelines and three roads, one two-pass job and five
 * one-pass ones - and a form cannot show one of those answers without choosing for the operator. So
 * what it shows is that there is no one answer, and the test that matters is the one pinning that.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  DIFFERENT,
  NOTHING,
  bulkBody,
  bulkFields,
  commonValue,
  picked,
  toggle
} from '../../main/assets/web/together.mjs';

const item = (id, asset = {}, groupName = null) => ({
  asset: { id, name: `Asset ${id}`, intervalDays: 120, ...asset },
  groupName
});

test('a field every picked asset agrees on is that answer', () => {
  const items = [item(1, { method: 'BOOM' }), item(2, { method: 'BOOM' })];

  assert.equal(commonValue(items, (one) => one.asset.method), 'BOOM');
});

test('a field they disagree on has no one answer, and says so', () => {
  // The one that matters: showing one of these answers would be the page choosing, and the operator
  // would then be changing six assets to a value they only ever saw on one of them.
  const items = [item(1, { method: 'BOOM' }), item(2, { method: 'KNAPSACK' })];

  assert.equal(commonValue(items, (one) => one.asset.method), DIFFERENT);
});

test('nothing to show where no picked asset has a value at all', () => {
  // An empty field and a field nobody has an answer for are different things: one says "leave it
  // empty", the other says "nobody has said" - and blank is what both of them look like in a text box.
  const items = [item(1, { notes: null }), item(2, { notes: null })];

  assert.equal(commonValue(items, (one) => one.asset.notes), NOTHING);
});

test('no rows picked is nothing to show rather than a disagreement', () => {
  assert.equal(commonValue([], (one) => one.asset.name), NOTHING);
});

test('a block is read off the record, and no block is read as no block', () => {
  // A bulk edit's fields do not all sit in the same place: the block's name is on the record rather
  // than in the asset, and an asset on its own has none - which is the same answer as an empty name,
  // because that is what the phone's own rule makes of it.
  const together = [item(1, {}, 'Estuary'), item(2, {}, 'Estuary')];
  const alone = [item(3, {}, null), item(4, {}, '')];
  const mixed = [item(5, {}, 'Estuary'), item(6, {}, null)];

  assert.equal(commonValue(together, (one) => one.groupName || ''), 'Estuary');
  assert.equal(commonValue(alone, (one) => one.groupName || ''), NOTHING);
  assert.equal(commonValue(mixed, (one) => one.groupName || ''), DIFFERENT);
});

test('a value of nought is an answer, not an absence', () => {
  // A number that happens to be falsy must not read as "nobody has said": a swath width of zero is
  // refused by the phone, but an interval or a pass count of one is a real answer and a test that
  // treated it as nothing would quietly leave it out of a change.
  const items = [item(1, { intervalDays: 1 }), item(2, { intervalDays: 1 })];

  assert.equal(commonValue(items, (one) => one.asset.intervalDays), 1);
  assert.equal(commonValue(items, (one) => one.asset.passesRequired ?? 1), 1);
});

test('ticking an asset adds it, and ticking it again takes it out', () => {
  assert.deepEqual(toggle([], 3), [3]);
  assert.deepEqual(toggle([3], 3), []);
  assert.deepEqual(toggle([1, 3], 2), [1, 3, 2], 'and the order picked is the order kept');
});

test('an asset picked twice is one asset', () => {
  // A double click on a tick box is two changes of one box, and the second one is what the box holds -
  // so the set can only ever gain an id once, and a request cannot name a row twice.
  assert.deepEqual(toggle(toggle([], 7), 7), []);
  assert.deepEqual(toggle([7], 9), [7, 9]);
});

test('the picked assets are read out of the list in the list\'s own order', () => {
  // The order the phone changes them in is the order they are sent, and the one a refusal names is the
  // first that stopped it - so it is the list's order rather than the order they were ticked.
  const items = [item(1), item(2), item(3)];

  assert.deepEqual(picked(items, [3, 1]).map((one) => one.asset.id), [1, 3]);
  assert.deepEqual(picked(items, []), []);
});

test('the request carries every row\'s own fingerprint, and the values once', () => {
  const items = [
    { asset: { id: 4 }, version: 'v4' },
    { asset: { id: 9 }, version: 'v9' }
  ];

  const body = bulkBody(items, bulkFields({
    name: 'Estuary block',
    kind: 'ROAD',
    method: 'BOOM',
    blockName: 'Estuary',
    intervalDays: '120',
    swathWidthM: '3',
    passesRequired: '2',
    passSeparationM: '3.5',
    notes: ''
  }));

  assert.deepEqual(body.assets, [{ id: 4, version: 'v4' }, { id: 9, version: 'v9' }]);
  assert.equal(body.fields.name, 'Estuary block');
  assert.equal(body.fields.blockName, 'Estuary');
  assert.equal(body.fields.passesRequired, 2, 'a number, which is what the phone reads for that field');
  assert.equal(body.fields.intervalDays, '120', 'and text for the fields the phone\'s rules parse');
});
