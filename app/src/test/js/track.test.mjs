/*
 * A track file dropped on the desk, from the desk's side of it.
 *
 * `track.mjs` is pure, so node runs the very file the browser does: what the desk will send, what name
 * it offers for the file, and what it says about the answer are tested here rather than read by eye
 * in `app.js`.
 *
 * What is *not* here is what a track file means - the line, the side tracks, whether the file's own
 * paths meet. That is the phone's reading (`TrackInterchange`), reached through `POST /api/gpx`, and
 * it is tested there. The page's half is the part only a browser can do: a file that is not a GPX,
 * KML or KMZ file at all, a file too big for the phone to take, the bytes turned into the base64 the
 * phone takes, and the words for what came back.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { TRACK_LIMIT, base64Of, importedNote, importProblem, trackName } from '../../main/assets/web/track.mjs';

const file = (name, size = 1024) => ({ name, size });

test('a dropped GPX file is taken', () => {
  assert.equal(importProblem(file('gully track.gpx')), null);
});

test('a dropped KML file is taken, which is what a Google Earth file is', () => {
  assert.equal(importProblem(file('fence.kml')), null);
});

test('a dropped KMZ file is taken, which is what a zipped KML file is', () => {
  assert.equal(importProblem(file('block.kmz')), null);
});

test('a dropped GeoJSON file is taken, under either of its two extensions', () => {
  assert.equal(importProblem(file('DOC_Tracks.geojson')), null);
  assert.equal(importProblem(file('tracks.json')), null);
});

test('a file that is not a track file is not sent, and is said so in words', () => {
  const problem = importProblem(file('fence.gif'));

  assert.match(problem, /not a GPX, KML, KMZ or GeoJSON file/);
  assert.match(
    problem,
    /ends in \.gpx, \.kml, \.kmz or \.geojson/,
    'and the words say what would be taken instead'
  );
  assert.match(problem, /fence\.gif/, 'naming the file it was handed');
});

test('the file type a computer reports is not asked about, because half of them are wrong', () => {
  // A GPX file exported by a tool that calls it application/octet-stream is still a GPX file, and the
  // name is the one thing every track file has.
  assert.equal(importProblem({ name: 'fence.GPX', size: 20, type: 'application/octet-stream' }), null);
  assert.equal(importProblem({ name: 'Block.KMZ', size: 20, type: 'application/zip' }), null);
});

test('a file too big for the phone is refused here rather than by the socket', () => {
  const problem = importProblem(file('great big ride.gpx', TRACK_LIMIT + 1));

  assert.match(problem, /great big ride\.gpx/);
  assert.match(problem, /256 KB/, 'the limit in the same words the phone would use');
  assert.equal(importProblem(file('just fits.gpx', TRACK_LIMIT)), null, 'and the limit itself is taken');
});

test('the limit the phone states is the limit the desk keeps', () => {
  // The state document carries the phone's own number, so the desk cannot drift from the socket it is
  // sending to - a page handed a smaller one refuses sooner, and a page handed nothing at all keeps
  // the phone's own rather than taking everything.
  assert.match(importProblem(file('ride.gpx', 3000), 2048), /2 KB/);
  assert.equal(importProblem(file('ride.gpx', 3000), 4096), null);
  assert.match(importProblem(file('ride.gpx', TRACK_LIMIT + 1), undefined), /256 KB/);
  assert.match(importProblem(file('ride.gpx', TRACK_LIMIT + 1), 'not a number'), /256 KB/);
  assert.match(importProblem(file('ride.gpx', TRACK_LIMIT + 1), 0), /256 KB/);
});

test('nothing dropped at all is said rather than thrown', () => {
  assert.match(importProblem(null), /no file/);
  assert.match(importProblem(undefined), /no file/);
});

test('the track is offered the file\'s own name, without its extension', () => {
  assert.equal(trackName(file('gully track.gpx')), 'gully track');
  assert.equal(trackName(file('fence line.kml')), 'fence line', 'a KML file loses its own extension too');
  assert.equal(trackName(file('block.kmz')), 'block', 'and so does a zipped one');
  assert.equal(trackName(file('DOC_Tracks.geojson')), 'DOC_Tracks', 'and so does a GeoJSON file');
  assert.equal(trackName(file('Gully Track.GPX')), 'Gully Track', 'the name keeps its own capitals');
  assert.equal(trackName(file('  spaced  .gpx')), 'spaced', 'and its own spaces are trimmed off it');
});

test('a file with no name to speak of still offers one', () => {
  assert.equal(trackName(file('.gpx')), 'Imported track', 'the phone\'s own fallback, word for word');
  assert.equal(trackName({}), 'Imported track');
});

test('a file\'s bytes are base64, which is how the phone takes a zipped file', () => {
  // A KMZ is a zip and not text, so the page cannot hand the phone characters: it hands it the bytes,
  // base64-encoded. The bytes have to survive exactly - a zip header or a UTF-8 accent is not a
  // character a naive encoder may guess at.
  const zipHeader = new Uint8Array([0x50, 0x4b, 0x03, 0x04]);
  assert.equal(base64Of(zipHeader), Buffer.from(zipHeader).toString('base64'));

  const accented = new TextEncoder().encode('Culvert — Waihōpai');
  assert.equal(base64Of(accented), Buffer.from(accented).toString('base64'));

  assert.equal(base64Of(new Uint8Array([])), '', 'and an empty file is an empty string, not a throw');
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
    importedNote('b.kml', { paths: [line, spur, spur], sideTracks: 2 }),
    /one line of 6 points with 2 side tracks\./
  );
});

test('a file whose segments did not meet is said to have been joined up', () => {
  const read = {
    paths: [[{ lat: -41.5, lng: 173.9 }, { lat: -41.6, lng: 173.9 }, { lat: -42.0, lng: 174.0 }, { lat: -42.1, lng: 174.0 }]],
    sideTracks: 0,
    segmentsDidNotMeet: true
  };

  const note = importedNote('two fences.kml', read);

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

test('a file of several tracks says how many more it held, rather than shrinking to its first', () => {
  // The desk draws one track at a time. A GeoJSON collection is many, so the note has to say the rest
  // are there and where to get them - otherwise the operator sees one drawing and thinks that was all.
  const read = {
    paths: [[{ lat: -46.6, lng: 168.3 }, { lat: -46.61, lng: 168.31 }]],
    sideTracks: 0,
    otherTracks: 3
  };

  const note = importedNote('DOC_Tracks.geojson', read);

  assert.match(note, /one line of 2 points/);
  assert.match(note, /holds 3 more tracks/);
  assert.match(note, /import it on the phone/);

  assert.doesNotMatch(
    importedNote('one.gpx', { paths: read.paths, sideTracks: 0, otherTracks: 0 }),
    /more tracks/,
    'a single-track file says nothing about other tracks'
  );
});
