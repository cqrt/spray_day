/*
 * A GPX file dropped on the desk, from the desk's side of it.
 *
 * `gpx.mjs` is pure, so node runs the very file the browser does: what the desk will send, what name
 * it offers for the file, and what it says about the answer are tested here rather than read by eye
 * in `app.js`.
 *
 * What is *not* here is what a GPX file means - the line, the side tracks, whether the file's own
 * segments meet. That is the phone's reading (`GpxInterchange`), reached through `POST /api/gpx`,
 * and it is tested there. The page's half is the part only a browser can do: a file that is not a
 * GPX file at all, a file too big for the phone to take, and the words for what came back.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { GPX_LIMIT, importedNote, importProblem, trackName } from '../../main/assets/web/gpx.mjs';

const file = (name, size = 1024) => ({ name, size });

test('a dropped GPX file is taken', () => {
  assert.equal(importProblem(file('gully track.gpx')), null);
});

test('a file that is not a GPX file is not sent, and is said so in words', () => {
  const problem = importProblem(file('fence.kml'));

  assert.match(problem, /not a GPX file/);
  assert.match(problem, /ends in \.gpx/, 'and the words say what would be taken instead');
  assert.match(problem, /fence\.kml/, 'naming the file it was handed');
});

test('the file type a computer reports is not asked about, because half of them are wrong', () => {
  // A GPX file exported by a tool that calls it application/octet-stream is still a GPX file, and the
  // name is the one thing every GPX file has.
  assert.equal(importProblem({ name: 'fence.GPX', size: 20, type: 'application/octet-stream' }), null);
});

test('a file too big for the phone is refused here rather than by the socket', () => {
  const problem = importProblem(file('great big ride.gpx', GPX_LIMIT + 1));

  assert.match(problem, /great big ride\.gpx/);
  assert.match(problem, /256 KB/, 'the limit in the same words the phone would use');
  assert.equal(importProblem(file('just fits.gpx', GPX_LIMIT)), null, 'and the limit itself is taken');
});

test('the limit the phone states is the limit the desk keeps', () => {
  // The state document carries the phone's own number, so the desk cannot drift from the socket it is
  // sending to - a page handed a smaller one refuses sooner, and a page handed nothing at all keeps
  // the phone's own rather than taking everything.
  assert.match(importProblem(file('ride.gpx', 3000), 2048), /2 KB/);
  assert.equal(importProblem(file('ride.gpx', 3000), 4096), null);
  assert.match(importProblem(file('ride.gpx', GPX_LIMIT + 1), undefined), /256 KB/);
  assert.match(importProblem(file('ride.gpx', GPX_LIMIT + 1), 'not a number'), /256 KB/);
  assert.match(importProblem(file('ride.gpx', GPX_LIMIT + 1), 0), /256 KB/);
});

test('nothing dropped at all is said rather than thrown', () => {
  assert.match(importProblem(null), /no file/);
  assert.match(importProblem(undefined), /no file/);
});

test('the track is offered the file\'s own name, without its extension', () => {
  assert.equal(trackName(file('gully track.gpx')), 'gully track');
  assert.equal(trackName(file('Gully Track.GPX')), 'Gully Track', 'the name keeps its own capitals');
  assert.equal(trackName(file('  spaced  .gpx')), 'spaced', 'and its own spaces are trimmed off it');
});

test('a file with no name to speak of still offers one', () => {
  assert.equal(trackName(file('.gpx')), 'Imported track', 'the phone\'s own fallback, word for word');
  assert.equal(trackName({}), 'Imported track');
});

test('a file read as one line says how many points it has', () => {
  const read = { paths: [[{ lat: -41.5, lng: 173.9 }, { lat: -41.6, lng: 173.9 }]], sideTracks: 0 };

  const note = importedNote('gully track.gpx', read);

  assert.match(note, /gully track\.gpx/);
  assert.match(note, /one line of 2 points/);
  assert.match(note, /Give it a name/, 'and says what the operator does next');
});

test('a file with side tracks says how many, in the drawing\'s own words', () => {
  const line = [{ lat: -41.5, lng: 173.9 }, { lat: -41.6, lng: 173.9 }];
  const spur = [{ lat: -41.6, lng: 173.9 }, { lat: -41.7, lng: 173.9 }];

  assert.match(
    importedNote('a.gpx', { paths: [line, spur], sideTracks: 1 }),
    /one line of 4 points with 1 side track\./
  );
  assert.match(
    importedNote('b.gpx', { paths: [line, spur, spur], sideTracks: 2 }),
    /one line of 6 points with 2 side tracks\./
  );
});

test('a file whose segments did not meet is said to have been joined up', () => {
  const read = {
    paths: [[{ lat: -41.5, lng: 173.9 }, { lat: -41.6, lng: 173.9 }, { lat: -42.0, lng: 174.0 }, { lat: -42.1, lng: 174.0 }]],
    sideTracks: 0,
    segmentsDidNotMeet: true
  };

  const note = importedNote('two fences.gpx', read);

  assert.match(note, /the file's own segments do not meet/);
  assert.match(note, /one line of 4 points/);
  assert.doesNotMatch(note, /side track/, 'nothing became a side track, so nothing says there is one');
});

test('a file the phone read as nothing at all is still answered about', () => {
  // Not a case the phone produces today - a file with no line in it is refused rather than answered
  // with - but the note is built from the reading, and a reading with no paths in it must not throw
  // in front of the operator with a file already dropped.
  assert.match(importedNote('odd.gpx', { paths: [] }), /one line of 0 points/);
  assert.match(importedNote('odd.gpx', undefined), /one line of 0 points/);
});
