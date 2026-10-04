/*
 * The desk's half of the DOC Tracks browser: the request that searches DOC through the phone, and
 * the body that imports the ticked tracks.
 *
 * Pure, and separate from `app.js` for the same reason `wire.mjs` is: node can run it without a
 * browser (`app/src/test/js/doc.test.mjs`, which CI runs), so what a search asks for and what an
 * import carries are pinned by a test rather than reviewed by eye.
 *
 * **The phone does the asking.** The page never reaches DOC's service itself: it asks the phone, and
 * the phone asks DOC. So the only decisions here are the query's shape and the import's.
 */

/**
 * The path `GET /api/doc` is asked at: the name, and where to look - the desk's own map view, or the
 * phone's own place.
 *
 * The place is the **phone's** fix, from the state document - not the browser's own location. The
 * work is the phone's, the phone is the one that can search DOC, and a laptop on a desk has no
 * business pretending to be the tractor. The view is the desk's, and it is a different question:
 * "the paddock I am looking at", not "where the tractor is". When both are given the view wins, so a
 * page that somehow checks both still sends one filter.
 */
export function docSearchPath(name, near, bounds, radiusKm) {
  const params = new URLSearchParams();
  const trimmed = String(name || '').trim();
  if (trimmed) params.set('name', trimmed);
  if (bounds) {
    params.set('bounds', [bounds.minLat, bounds.minLng, bounds.maxLat, bounds.maxLng].join(','));
  } else if (near) {
    params.set('near', `${near.lat},${near.lng}`);
    const radius = Number(radiusKm);
    if (Number.isFinite(radius) && radius > 0) params.set('radiusKm', String(radius));
  }
  const query = params.toString();
  return query ? `/api/doc?${query}` : '/api/doc';
}

/**
 * The body `POST /api/doc/import` carries: the ticked tracks, each with the geometry the phone
 * already showed. One request for the lot, so an import is one act.
 */
export function docImportBody(tracks) {
  return {
    tracks: tracks.map((track) => ({
      id: track.id,
      name: track.name,
      paths: track.paths
    }))
  };
}

/**
 * The word under the desk's track list: the phone's own sentence when it has one, and otherwise how
 * many tracks there are - saying so when a search showed only the first of more.
 */
export function docSearchNote(search) {
  if (!search || typeof search !== 'object') return '';
  if (search.message) return search.message;

  const count = (search.tracks || []).length;
  if (count === 0) return 'No DOC tracks matched.';
  if (search.capped) return `Showing the first ${count} matches. Narrow the search to see the rest.`;
  return count === 1 ? '1 track' : `${count} tracks`;
}

/**
 * The kinds present in these tracks, each once, sorted, for a filter row to offer.
 *
 * Taken from what a search returned rather than from a list of DOC's taxonomy kept here: the filter
 * offers what is actually on the page, so it cannot offer a kind the service no longer uses or miss
 * one it has learned since.
 */
export function docKindsOf(tracks) {
  const kinds = [];
  for (const track of tracks || []) {
    const kind = track.kind ? String(track.kind).trim() : '';
    if (kind && !kinds.includes(kind)) kinds.push(kind);
  }
  return kinds.sort();
}

/**
 * The tracks to show: those of the picked `kinds` (all when none is picked), ordered by `sort`.
 *
 * `nearest` uses the `distanceM` the phone sent - so the desk's order is the phone's, computed from
 * the phone's own fix. A track with no distance, because the phone had no fix, sorts last rather than
 * first, and ties fall back to the name.
 */
export function docShowing(tracks, kinds, sort) {
  const chosen = kinds || [];
  const all = tracks || [];
  const ofKind = chosen.length === 0 ? all : all.filter((track) => chosen.includes(track.kind));
  const byName = (a, b) => a.name.localeCompare(b.name);

  if (sort !== 'nearest') return [...ofKind].sort(byName);
  return [...ofKind].sort((a, b) => {
    const da = typeof a.distanceM === 'number' ? a.distanceM : Infinity;
    const db = typeof b.distanceM === 'number' ? b.distanceM : Infinity;
    // `da - db` is NaN when both are Infinity; `|| byName` then takes over.
    return da - db || byName(a, b);
  });
}
