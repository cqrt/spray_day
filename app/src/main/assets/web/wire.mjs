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
 * The drawing part of a line's save: the line the operator has just drawn or tidied, and the side
 * tracks the phone last handed over.
 *
 * `record` is the state document's own record, whose `paths` are the track's paths as the phone holds
 * them, line first. The first of them is replaced by the drawn [line] and the rest travel back
 * untouched - which is what makes changing the line of a track with a spur a change to the line rather
 * than a hole where the spur was.
 */
export function drawingBody(record, line) {
  const paths = record?.paths ?? [];
  if (paths.length < 2) return { points: line };
  return { paths: [line, ...paths.slice(1)] };
}
