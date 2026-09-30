/*
 * Whether the desk is drawn light or dark.
 *
 * Pure, and separate from `app.js` for the same reason `camera.mjs` is: node can run it without a
 * browser (`app/src/test/js/theme.test.mjs`, which CI runs), so what the memory keeps and what it
 * refuses are pinned by a test rather than reviewed by eye.
 *
 * **The desk's own choice, not the phone's and not the computer's.** Two laptops open on the same
 * phone are two desks, and one of them may be in a cab at night while the other sits beside a window:
 * so the theme is a light or a dark, chosen by the button in the bar, and kept in the browser's own
 * store. The computer's own setting is not asked - the choice is a manual one, deliberately.
 *
 * **Only the theme is kept.** The store outlives the page and is readable by anything else served
 * from the same address, so what goes in it is one word - "light" or "dark" - and nothing else: no
 * token, no asset, no name.
 */

/** What the desk calls this in the browser's store. */
export const THEME_KEY = 'sprayday-desk-theme';

export const LIGHT = 'light';
export const DARK = 'dark';

/** Whether a value is one of the two words the store may hold. Anything else is not a theme. */
export function isTheme(value) {
  return value === LIGHT || value === DARK;
}

/**
 * The theme the last visit left, or light when there is none to be had - which is how a desk opens
 * light the first time, and how it opens light again if the store is unreadable or was written by
 * something else.
 */
export function recallTheme(storage) {
  if (!storage) return LIGHT;
  let stored = null;
  try {
    stored = storage.getItem(THEME_KEY);
  } catch (error) {
    return LIGHT;
  }
  return isTheme(stored) ? stored : LIGHT;
}

/**
 * Keeps the theme for the next time this page is built.
 *
 * A store that refuses to be written to - storage switched off, a private window, a full quota - is
 * not a failure worth telling anybody about: a desk that cannot remember it was dark is still a desk.
 */
export function rememberTheme(storage, theme) {
  if (!storage || !isTheme(theme)) return;
  try {
    storage.setItem(THEME_KEY, theme);
  } catch (error) {
    // Nothing to do about it and nothing to say: the operator loses a convenience, not their work.
  }
}
