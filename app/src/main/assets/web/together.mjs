/*
 * Working on several assets at once: which rows are picked, and what one form can say about them.
 *
 * Pure, and separate from `app.js` for the same reason `wire.mjs` and `gpx.mjs` are: node can run it
 * without a browser (`app/src/test/js/together.test.mjs`, which CI runs), so the arithmetic behind a
 * bulk edit is pinned by a test rather than reviewed by eye.
 *
 * **The whole design is in one question**: what does a field mean when it is about six assets? Two of
 * them are in a block and four are not, three are fencelines and three are roads, one takes two passes
 * and the rest take one. A form cannot show one value, so it shows that there is **no one value** and
 * leaves the field alone until the operator ticks it - which is what `commonValue` answers.
 *
 * Nothing here decides what the phone will take: the field values travel to the phone as text and are
 * judged there, by the same rules the phone's own edit form uses.
 */

/**
 * The marker for a field the picked assets do not agree on.
 *
 * A string rather than null so it can be typed into a field and read off a label with no special case
 * at either end: nothing a farm is called, and not a value any field can be given.
 */
export const DIFFERENT = 'More than one';

/** Nothing to say about a field: no row has a value for it at all. */
export const NOTHING = '';

/**
 * The field every picked asset shares, `DIFFERENT` when they do not agree, or `NOTHING` when none of
 * them has one.
 *
 * [read] is how to read the field off a record, because they do not all sit in the same place: a
 * block's name is on the record rather than in the asset, an interval is a number, a separation can be
 * absent. Reading it here rather than at each call site is what makes one rule out of six fields.
 */
export function commonValue(items, read) {
  if (!items.length) return NOTHING;
  const first = read(items[0]);
  if (!items.every((item) => read(item) === first)) return DIFFERENT;
  return first === null || first === undefined ? NOTHING : first;
}

/**
 * Adding an asset to or taking it out of the picked set.
 *
 * Returns a **new** array, ids in the order they were picked, each id once: a row clicked twice by a
 * double click is one asset, and the order is the order the operator chose them in - which is the order
 * they are changed in on the phone, so the sentence about a refusal names the first one that stopped it.
 */
export function toggle(selected, id) {
  return selected.includes(id)
    ? selected.filter((one) => one !== id)
    : [...selected, id];
}

/** The assets the list is showing that are picked, in the list's own order. */
export function picked(items, selected) {
  return items.filter((item) => selected.includes(item.asset.id));
}

/**
 * The request a bulk edit sends: the rows, each with the fingerprint of the copy the desk read, and the
 * values to put on all of them.
 *
 * The version per row is the whole safety of the thing. Six assets changed by one form is still six
 * rows somebody may have edited on the phone a minute ago, and the phone refuses the **lot** when any
 * one of them has moved - so what a desk can never do is write over a change it never saw.
 */
export function bulkBody(items, fields) {
  return {
    assets: items.map((item) => ({ id: item.asset.id, version: item.version })),
    fields
  };
}

/** The values a bulk form holds, as text, in the shape the phone's own edit body uses. */
export function bulkFields(values) {
  return {
    name: values.name,
    kind: values.kind,
    method: values.method,
    blockName: values.blockName,
    intervalDays: values.intervalDays,
    swathWidthM: values.swathWidthM,
    passesRequired: Number(values.passesRequired),
    passSeparationM: values.passSeparationM,
    notes: values.notes
  };
}
