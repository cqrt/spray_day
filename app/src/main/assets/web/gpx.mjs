/*
 * A GPX file dropped on the desk, from the desk's side of it.
 *
 * Pure, and separate from `app.js` for the same reason `wire.mjs` is: node can run it without a
 * browser (`app/src/test/js/gpx.test.mjs`, which CI runs), so what the page will and will not take
 * is pinned by a test rather than reviewed by eye.
 *
 * **What the file *is* is not decided here.** A GPX file becomes a line and its side tracks on the
 * phone, by the same reading the phone's own list screen imports a file with - the page could only
 * decide that for itself by keeping a second copy of the rule, and the copy that fell behind would
 * be whichever side of the wire is the rarer. What is here is what only a browser can know: whether
 * the thing dropped is a file this desk should send at all, what name to offer for it, and how to
 * say what came back.
 */

/**
 * The largest file the desk will send, in bytes.
 *
 * The phone's own limit and not the page's - a body over it is refused by the socket before any of
 * the phone's own rules see it, which would be a refusal about the size of a request rather than
 * about the file. Kept to the same number so the message here and the wall there are the same wall;
 * `app.js` takes it from the phone's state document, and this is only what a test can stand on.
 */
export const GPX_LIMIT = 256 * 1024;

/** The extension a file has to carry: what a GPX file is called, on every computer. */
const GPX_EXTENSION = '.gpx';

/** What a file with no name of its own is read as, off the file's own name. */
const FALLBACK_NAME = 'Imported track';

/** The size the state document named, or the phone's own if a page was handed none. */
function limitOf(maxBytes) {
  return Number.isInteger(maxBytes) && maxBytes > 0 ? maxBytes : GPX_LIMIT;
}

/**
 * Why this file will not be sent, in words for the operator, or null when it will be.
 *
 * Judged on the file's **name** rather than on its type, because the type a browser reports for a
 * GPX file is whatever the computer that wrote it decided, and half the computers that write one
 * call it something else. The name is the one thing every GPX file has.
 */
export function importProblem(file, maxBytes = GPX_LIMIT) {
  if (!file) return 'There was no file in that. Drop a GPX file on the map and try again.';
  const name = String(file.name || '').toLowerCase();
  if (!name.endsWith(GPX_EXTENSION)) {
    return `"${file.name}" is not a GPX file. Drop a file whose name ends in .gpx.`;
  }
  const limit = limitOf(maxBytes);
  if (typeof file.size === 'number' && file.size > limit) {
    return `"${file.name}" is ${kilobytes(file.size)} and the phone takes GPX files up to ` +
      `${kilobytes(limit)}.`;
  }
  return null;
}

/**
 * The name to offer for a track made from this file: the file's own name, without its extension.
 *
 * The phone's own importer does exactly this with the name its file picker hands it - the file's
 * name rather than whatever is written inside the file, because a file called after the paddock it
 * was drawn for is named for the job and not for whatever tool exported it. It is a starting point
 * in a form the operator can type over, so nothing here is a decision about what the track is
 * called.
 */
export function trackName(file) {
  const name = stripExtension(String((file && file.name) || '')).trim();
  return name || FALLBACK_NAME;
}

/** A file's name as a track's name: what is left of it when its extension is taken off. */
function stripExtension(name) {
  return name.toLowerCase().endsWith(GPX_EXTENSION) ? name.slice(0, -GPX_EXTENSION.length) : name;
}

/**
 * What to say about a file the phone has read, in the phone's own numbers.
 *
 * [read] is what `POST /api/gpx` answered with. A file whose segments did not meet was joined up
 * into one line, and that is worth saying out loud: a track with a jump in it is a track somebody
 * should look at before it is saved. The count is every vertex of every path, which is the same
 * count the drawing's own box shows a moment later.
 */
export function importedNote(fileName, read) {
  const paths = (read && read.paths) || [];
  const points = paths.reduce((total, path) => total + path.length, 0);
  const sideTracks = (read && read.sideTracks) || 0;
  const joined = read && read.segmentsDidNotMeet;

  const said = joined
    ? `read as one line of ${points} points, because the file's own segments do not meet`
    : sideTracks === 0
      ? `one line of ${points} points`
      : sideTracks === 1
        ? `one line of ${points} points with 1 side track`
        : `one line of ${points} points with ${sideTracks} side tracks`;

  return `${fileName}: ${said}. Give it a name, then save it to the phone.`;
}

/** A size in words an operator reads at a glance: whole kilobytes, and never "0 KB". */
function kilobytes(bytes) {
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
