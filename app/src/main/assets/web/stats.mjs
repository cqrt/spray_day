/*
 * What the whole farm comes to: the phone's own corner box, on a desk.
 *
 * Pure, and separate from `app.js` for the same reason `find.mjs` is separate: node runs the very
 * file the browser does (`app/src/test/js/stats.test.mjs`, which CI runs), so the counts and the
 * figures the desk puts in its corner are pinned by a test rather than read by eye.
 *
 * **The phone's arithmetic, not a second opinion.** Every figure here is the rule the phone's own
 * map box uses: the length of every line added up, and an area made of a ring's own ground and a
 * line's estimates (never one read as the other). The two formatters are here
 * too, and for the same reason: they are the phone's own words for a figure (`formatDistance` and
 * `formatArea` on the phone), and a copy kept in `app.js` as well would be a second copy to fall
 * behind. A desk that totalled the farm its own way would be a second answer to one question, and
 * the operator is the one who would catch it.
 */

/**
 * "850 m" / "2.35 km" - a length or a distance, in the phone's own words.
 *
 * `formatDistance`, to the letter. Zero is not a measurement and reads as one, so a caller says
 * nothing of a zero rather than asking this about it.
 */
export function metresText(metres) {
  if (!metres) return 'not measured';
  return metres >= 1000 ? `${(metres / 1000).toFixed(2)} km` : `${Math.round(metres)} m`;
}

/**
 * "420 m²" / "1.2 ha" - a piece of ground, in the phone's own words.
 *
 * `formatArea`, to the letter: square metres until there are ten thousand of them and hectares after,
 * because the number an operator reads off a phone and the number a desk shows them have to be the same
 * figure said the same way. Nothing is said of a zero: ground that is not there yet is not "0 m²".
 */
export function areaText(squareMetres) {
  if (!squareMetres) return '';
  return squareMetres >= 10_000
    ? `${(squareMetres / 10_000).toFixed(1)} ha`
    : `${Math.round(squareMetres)} m²`;
}

/**
 * What the whole farm comes to, from the assets the phone carries.
 *
 * [items] is `state.assets` as the phone sends it: each one its asset, with its shape, length, swath
 * width and passes, and its own due state. The rules are the phone's own, item by item:
 *
 *  - a never-sprayed asset counts with the overdue ones, because both need going over and the map
 *    paints them the same colour;
 *  - an area is a ring's own **measured** ground, or a line's **estimate** from a swath width, and
 *    both passes of a two-pass line are ground rather than the same ground twice;
 *  - a place encloses nothing and has no length, so it adds to the counts and to nothing else.
 */
export function farmStats(items) {
  const all = items ?? [];
  const inState = (status) => all.filter((item) => item.dueStatus === status).length;

  // The phone's own question about a ring: does it enclose ground it has measured? A line or a place
  // answers no, and a ring whose corners measure nothing is not measured either.
  const isGround = (item) => item.asset.shape === 'AREA' && (item.asset.areaM2 ?? 0) > 0;
  const isEstimated = (item) =>
    !isGround(item) && item.asset.swathWidthM && (item.asset.lengthM ?? 0) > 0;

  return {
    count: all.length,
    overdue: inState('OVERDUE') + inState('NEVER_SPRAYED'),
    dueSoon: inState('DUE_SOON'),
    notDue: inState('NOT_DUE'),
    lengthM: all.reduce((total, item) => total + (item.asset.lengthM ?? 0), 0),
    areaSqm: all.reduce((total, item) => {
      if (isGround(item)) return total + item.asset.areaM2;
      if (isEstimated(item)) {
        return total + item.asset.lengthM * item.asset.swathWidthM * item.asset.passesRequired;
      }
      return total;
    }, 0),
    areaAssetCount: all.filter((item) => isGround(item) || isEstimated(item)).length
  };
}

/**
 * The farm's figures, said the way the phone's own box says them.
 *
 * Two lines, and no more: how long the farm is and how much ground it covers. Every line is left out
 * when it is not known rather than shown as a zero - a farm of places has no length at all - and an
 * area covering only some of the assets says so, because a figure quietly covering half the farm is
 * the kind that ends up in a spray diary as though somebody had surveyed it. Whether anything is
 * still to be driven is not said here: the counts above already say how much of the farm is left, and
 * a line repeating them is a line the desk and the phone both pay map for.
 */
export function farmStatsLines(stats) {
  const lines = [];
  if (stats.lengthM > 0) lines.push(`Total length ${metresText(stats.lengthM)}`);
  if (stats.areaSqm > 0) {
    const partial = stats.areaAssetCount < stats.count;
    lines.push(`Total area about ${areaText(stats.areaSqm)}${partial ? ` from ${stats.areaAssetCount} of them` : ''}`);
  }
  return lines;
}
