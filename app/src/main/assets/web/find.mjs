/*
 * What the desk is showing: the list's rows, and the work the map draws.
 *
 * Pure, and separate from `app.js` for the same reason `words.mjs` is: node runs the very file the
 * browser does (`app/src/test/js/find.test.mjs`, which CI runs), so which rows are on the screen is
 * pinned by a test rather than read by eye.
 *
 * The type is a plain name comparison because the phone sends the **resolved** kind: a track drawn
 * before the kinds existed arrives as FENCELINE or OTHER_PLACE rather than as INFRASTRUCTURE, so the
 * page needs no copy of the rule that reads an old kind back. That is the point of doing it on the
 * phone - this page has already been caught keeping its own copy of the phone's words (v0.6.43), and
 * the old-kind rule is the same trap.
 */

/**
 * The rows to show, in the order they were handed over.
 *
 * [needle] is what was typed, matched anywhere in the name and without case, which is what the box has
 * always done; blank matches everything. [kind] is the value the Type list is showing, or null for
 * *Anything*. Both have to match - a filter that let one of them go would be a filter that lies about
 * what it is doing.
 */
export function visibleAssets(assets, needle, kind) {
  const wanted = (needle ?? '').trim().toLowerCase();
  return (assets ?? []).filter((item) => {
    if (kind && item.asset.kind !== kind) return false;
    return !wanted || item.asset.name.toLowerCase().includes(wanted);
  });
}

/**
 * The work's features, narrowed to the rows the desk is showing.
 *
 * The list and the map are one answer to one question. Naming a kind beside a map still drawing all
 * of the work is the page arguing with itself: an operator who has asked to see the buildings is
 * looking at the map to see *where* they are, and forty tracks around them is the wrong answer. So
 * what narrows the list narrows the map - both the type and the typed words - and this is the one
 * place that does it.
 *
 * Chosen by the id the phone gave each asset, which is the same id the rows carry and the same one
 * the map's own layers already filter on, so there is no second way of saying which asset is which.
 * The collection's other keys are carried over untouched: this narrows what the map draws, and is
 * not a second opinion about the document.
 */
export function visibleFeatures(features, shown) {
  const ids = new Set((shown ?? []).map((item) => item.asset.id));
  const all = features?.features ?? [];
  return { ...features, features: all.filter((feature) => ids.has(feature.properties.id)) };
}
