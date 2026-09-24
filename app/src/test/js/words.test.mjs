/*
 * What the desk calls things.
 *
 * `words.mjs` is pure, so node runs the very file the browser does: what the card says an asset is, and
 * what it says about a spray method, are tested here rather than read by eye in `app.js`.
 *
 * The bug these pin, from v0.6.40: the page kept its own three kind names, so an asset of a kind the
 * phone had learned since - Building - was spoken as the phone's own code, in lower case. The card read
 * "building", and with the shape's word after it, "building, a place": one kind, said twice.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { kindText, methodText } from '../../main/assets/web/words.mjs';

/** The phone's own list, as it arrives in the state document: the eight kinds, the phone's words. */
const kinds = [
  { value: 'TRACK', label: 'Track' },
  { value: 'ROAD', label: 'Road' },
  { value: 'FENCELINE', label: 'Fenceline' },
  { value: 'BUILDING', label: 'Building' },
  { value: 'OTHER_PLACE', label: 'Other place' }
];

test('a kind the phone names is said in the phone\'s own word', () => {
  assert.equal(kindText('BUILDING', kinds), 'Building');
  assert.equal(kindText('OTHER_PLACE', kinds), 'Other place', 'not "other place", and not twice');
  assert.equal(kindText('TRACK', kinds), 'Track');
});

test('a track drawn before the kinds existed reads as what it is on the ground', () => {
  // Stored as INFRASTRUCTURE, which is not a choice the phone offers any more.
  assert.equal(kindText('INFRASTRUCTURE', kinds), 'Fenceline or stopbank');
});

test('a kind the phone does not name is said in farm words rather than in its code', () => {
  assert.equal(kindText('SIGN', kinds), 'sign', 'a page older than the phone still says something');
  assert.equal(kindText('OTHER_PLACE', []), 'other place', 'and no code is ever shouted');
  assert.equal(kindText(undefined, kinds), '', 'nothing to say, and nothing invented');
});

test('a method the phone names is said in the phone\'s own words', () => {
  const methods = [{ value: 'BOOM', label: 'Boom' }, { value: 'NONE', label: 'Not recorded' }];

  assert.equal(methodText('BOOM', methods), 'Boom');
  assert.equal(methodText('NONE', methods), 'Not recorded', 'which is not the page\'s own "Not set"');
});

test('a method the phone does not name is said in farm words, not in its code', () => {
  assert.equal(methodText('TWO_PASSES', []), 'two passes');
  assert.equal(methodText(undefined, []), '');
});
