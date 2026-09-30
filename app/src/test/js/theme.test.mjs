/*
 * Whether the desk is drawn light or dark.
 *
 * `theme.mjs` is pure, so node runs the very file the browser does: the two words the store may hold,
 * and what comes back when it holds neither or holds something else, are pinned here rather than read
 * by eye in a browser.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { DARK, isTheme, LIGHT, recallTheme, rememberTheme, THEME_KEY } from '../../main/assets/web/theme.mjs';

/** A store with the two methods the browser's own has, held in memory. */
function store(entries = {}) {
  const map = new Map(Object.entries(entries));
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, value)
  };
}

test('the theme is light or dark, and nothing else is a theme', () => {
  assert.equal(isTheme(LIGHT), true);
  assert.equal(isTheme(DARK), true);
  assert.equal(isTheme('sepia'), false);
  assert.equal(isTheme(null), false);
  assert.equal(isTheme(undefined), false);
});

test('a desk with no memory opens light', () => {
  assert.equal(recallTheme(null), LIGHT);
  assert.equal(recallTheme(store()), LIGHT);
});

test('a remembered theme is read back, light and dark alike', () => {
  assert.equal(recallTheme(store({ [THEME_KEY]: DARK })), DARK);
  assert.equal(recallTheme(store({ [THEME_KEY]: LIGHT })), LIGHT);
});

test('a store written by something else is not a theme, and opens light', () => {
  assert.equal(recallTheme(store({ [THEME_KEY]: '{"theme":"dark"}' })), LIGHT);
  assert.equal(recallTheme(store({ [THEME_KEY]: 'Dark' })), LIGHT);
});

test('remembering writes the one word, refuses anything else, and never throws', () => {
  const memory = store();
  rememberTheme(memory, DARK);
  assert.equal(memory.getItem(THEME_KEY), DARK);

  rememberTheme(memory, 'sepia'); // refused, so the last real choice stands
  assert.equal(memory.getItem(THEME_KEY), DARK);

  rememberTheme(null, DARK); // no store at all is no failure
});
