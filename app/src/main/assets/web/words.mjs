/*
 * The page's words for the phone's own codes.
 *
 * Pure, and separate from `app.js` for the same reason `geometry.mjs` is separate from `edit.js`: node
 * can run it without a browser (`app/src/test/js/words.test.mjs`, which CI runs), so what the desk says
 * a building is - and what it says for a track drawn before buildings existed - is pinned by a test
 * rather than reviewed by eye.
 *
 * **The phone's words, not the page's.** Every list handed in here is the phone's own list of choices,
 * with the phone's own labels on it, arrived in the state document. This page used to keep its own copy
 * of three kind names, and when the phone learned five more kinds it went on saying them in the phone's
 * codes: an asset of the kind *Building* read as `building`. A word kept in two places is a word that
 * falls behind in one of them.
 */

/**
 * The words this page used to keep, for the one value the phone no longer offers.
 *
 * A track drawn before the kinds existed is stored as `INFRASTRUCTURE`, and the phone reads it back as
 * a fenceline or a stopbank by its shape. The phone's choice list has no entry for it - it is not a
 * choice, it is what a line drawn long ago means - so without this the card would say the code.
 * Nothing else belongs here: a kind the phone offers arrives named.
 */
const LEGACY_KIND_TEXT = { INFRASTRUCTURE: 'Fenceline or stopbank' };

/**
 * The phone's own word for a kind - "Track", "Fenceline", "Building", "Other place".
 *
 * [kinds] is the phone's own list of choices, each `{ value, label }`, from the state document. A kind
 * with no word there is said in farm words rather than in the phone's naming, so a page older than the
 * phone still says something an operator can read: `OTHER_PLACE` would come out as "other place".
 */
export function kindText(kind, kinds = []) {
  const spoken = (kinds ?? []).find((one) => one.value === kind);
  if (spoken) return spoken.label;
  return LEGACY_KIND_TEXT[kind] || farmWords(kind);
}

/**
 * The phone's own word for a spray method: "Not recorded", "Boom" or "Knapsack".
 *
 * Read out of the state document rather than kept here. This page used to hold its own three words,
 * which is a second copy of the phone's phrase table - one that said "Not set" where the phone says
 * "Not recorded", and one that would have gone on saying it when the phone learns a method this page
 * has never heard of. As a lookup, a method added on the phone arrives already named.
 */
export function methodText(method, methods = []) {
  const spoken = (methods ?? []).find((one) => one.value === method);
  return spoken ? spoken.label : farmWords(method);
}

/** A code said the way a person would say it: not shouted, and no underscores. */
function farmWords(code) {
  return String(code ?? '').toLowerCase().replace(/_/g, ' ');
}
