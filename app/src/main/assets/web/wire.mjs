/*
 * What a write says about where a track goes.
 *
 * Pure, and separate from `app.js` for the same reason `geometry.mjs` is separate from `edit.js`: node
 * can run it without a browser (`app/src/test/js/wire.test.mjs`, which CI runs), so the rule about what
 * a save carries is pinned by a test rather than reviewed by eye.
 *
 * **The phone's rule, in the page's hands.** A line's write carries `points` - one path - or `paths` -
 * the line and its side tracks. The phone refuses a single path for a track that has side tracks,
 * because writing it would drop every spur, and it refuses a different number of paths than the track
 * has, because adding and taking side tracks off is still the phone's job. So a page that has been
 * handed the paths sends them back, and a page that has not (or a track with no side tracks) keeps
 * sending the one-path body every earlier page sent.
 */

/**
 * What a save carries about where a track goes: the paths the desk is holding.
 *
 * One path is sent as `points`, which is the body every page before this one sent, and two or more as
 * `paths` - the line first, then its side tracks. The phone refuses a single path for a track that has
 * side tracks (rather than silently dropping them), so which shape a save has matters: this is the one
 * decision in the page's own logic about a write, which is why it lives in a module node can test.
 */
export function drawingBody(paths) {
  if (paths.length > 1) return { paths };
  return { points: paths[0] ?? [] };
}
