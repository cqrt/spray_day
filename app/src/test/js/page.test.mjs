/*
 * The page's own markup, held to the rules the rest of the page assumes.
 *
 * This file exists because of one real fault: the Import button was written with `disabled` in
 * `index.html` so that it could not be pressed before the map was ready - and nothing ever took that off
 * again, so the button was greyed out for good and clicking it did nothing at all. Every check that ran
 * against the page said "the import button is disabled" and it was written down as correct, because the
 * question being asked was what the page *is* rather than what the markup *promises*.
 *
 * So the rule is stated where it can be tested without a browser: **a control that starts disabled must
 * be named here as one the page's code turns on**, and the page's code must actually say so. A button
 * that starts off and is never turned on cannot be pressed by anybody, which is not a state any operator
 * can work out for themselves.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../../main/assets/web/index.html', import.meta.url), 'utf8');
const pageCode = readFileSync(new URL('../../main/assets/web/app.js', import.meta.url), 'utf8');

/**
 * The controls that start disabled, and why each one is.
 *
 * Nothing else may start disabled. If a control is added that needs to, it belongs in this table with the
 * sentence saying what turns it on - which is the check on the next line, so the table cannot be a list of
 * good intentions.
 */
const STARTS_DISABLED = {
  draw: "field('draw').disabled = false"
};

test('every control that starts disabled is one the page turns on, and says so', () => {
  const disabled = [...html.matchAll(/<button id="([^"]+)"[^>]*\bdisabled\b[^>]*>/g)].map((m) => m[1]);

  for (const id of disabled) {
    const how = STARTS_DISABLED[id];
    assert.ok(
      how,
      `"${id}" starts disabled and nothing says what turns it on. A button nobody can press is not a ` +
        'control: either open it in the markup, or name it in STARTS_DISABLED with the code that opens it.'
    );
    assert.ok(
      pageCode.includes(how),
      `"${id}" is listed as opened by \`${how}\`, and the page's own code does not contain that line - ` +
        'so the button is still off for everybody.'
    );
  }

  // And the other way round: a control listed here that no longer starts disabled is a stale promise.
  for (const id of Object.keys(STARTS_DISABLED)) {
    assert.ok(
      disabled.includes(id),
      `"${id}" is listed as starting disabled and no longer does - take it out of STARTS_DISABLED.`
    );
  }
});

test('the Import a track file button is not one of them', () => {
  // The fault this file was written for, pinned on its own: a file becomes a drawing, a drawing needs a
  // map with a style - and that is a question for the press to answer, not a reason to grey the button
  // out. The page says so in words instead: `mapIsNotReady`.
  const button = html.match(/<button id="import"[^>]*>/)[0];

  assert.ok(!/\bdisabled\b/.test(button), `the import button starts disabled again: ${button}`);
  assert.ok(
    pageCode.includes('function mapIsNotReady()'),
    'and the page no longer has the guard that says why a file cannot be taken yet'
  );
});

test('every control the page reaches for is in the markup', () => {
  // The other half of the same fault: a page that asks for an element that is not there throws somewhere
  // unrelated, and the screen that goes quiet is not the one with the mistake in it.
  const wanted = new Set([...pageCode.matchAll(/(?:getElementById|field)\('([a-zA-Z0-9_-]+)'\)/g)]
    .map((m) => m[1]));
  const have = new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1]));

  const missing = [...wanted].filter((id) => !have.has(id));
  assert.deepEqual(missing, [], `the page asks for elements that are not in the markup: ${missing}`);
});
