/*
 * What is drawn under the asset that is picked out.
 *
 * Pure, and separate from `app.js` for the same reason `wire.mjs` is: node can run it without a browser
 * (`app/src/test/js/glow.test.mjs`, which CI runs), so what the mark is made of is pinned by a test rather
 * than reviewed by eye.
 *
 * **The phone's shape, the page's mark.** The desk has a selection and the phone's own map has none, so
 * this is a layer of the page's - but it is built *out of one of the phone's own layers*: the same source,
 * the same filter (narrowed to the one asset), the same width, and the same dash pattern. The dash pattern
 * is the part worth having in a file a test can read: an edge that filled in the gaps between a fenceline's
 * dots would say "solid" about a fenceline, which is the one thing those dots are there to say. So what the
 * mark is the *shape* of stays the phone's decision.
 *
 * A line gets a **white edge** under it - the colour chosen for it, and the page's own paper: white is the
 * one colour the app's traffic light never uses, so a line goes on saying when it is due while the edge
 * round it says only "this is the one in hand". It is the same white the drawing's own handles use, and the
 * argument is the house's own (see `houseImage`): a thing has to be findable over dark winter imagery.
 * A place, whose house is a picture rather than a line, keeps a soft **disc of its own colour** behind it
 * instead: a white edge round a house that already has a white edge round it would say nothing.
 */

/** No asset picked out: an id no asset has, which is how a filter says "draw nothing". */
export const NO_ASSET = -1;

/** The page's own paper, and the white the line's edge is drawn in. */
const CASING_COLOUR = '#ffffff';

/**
 * The edge under a line: wider than the line by this much in total, blurred by this, and this strong.
 *
 * Not a haze: an edge that is soft enough to spread over a paddock is soft enough to disappear over one.
 */
const CASING_WIDTH_EXTRA = 10;
const CASING_BLUR = 2;
const CASING_OPACITY = 0.9;

/**
 * The glow behind a place, in the pick's own colour: the house is a picture about twenty pixels across, so
 * the disc is bigger than that by enough to be seen round it, and its blur is mild - a disc blurred all the
 * way to nothing is hidden *by* the picture it is behind.
 */
const PLACE_RADIUS = 22;
const PLACE_BLUR = 0.5;
const PLACE_OPACITY = 0.6;

/** The width a mark falls back to when the phone's own layer does not say one. */
const DEFAULT_WIDTH = 4;

/** The page's own name for the halo under a layer of the phone's work. */
export function haloId(phoneLayerId) {
  return `sprayday-desk-glow-${phoneLayerId}`;
}

/**
 * The phone's own test for a layer, with "and it is this asset" added.
 *
 * Flattened rather than nested when the phone's filter is already an `all`: the layers of a kind carry
 * `["all", kind is this, shape is that]`, and `["all", ["all", ...], ...]` is one more `all` than the
 * question asks for - which is the kind of thing that makes a filter unreadable in a debugger.
 */
export function pickOut(phoneFilter, id) {
  const isThisAsset = ['==', ['get', 'id'], id];
  if (!phoneFilter) return isThisAsset;
  return phoneFilter[0] === 'all' ? [...phoneFilter, isThisAsset] : ['all', phoneFilter, isThisAsset];
}

/**
 * The mark that belongs under one of the phone's layers: its shape, narrowed to the picked-out asset, and
 * the white the page draws a line's edge in - or, for a place, a disc in the pick's own colour.
 *
 * Nothing is drawn until something is picked out: with no id the filter names an id no asset has, and the
 * layer draws nothing at all.
 */
export function haloLayer(phoneLayer, id = NO_ASSET) {
  const paint = phoneLayer.paint || {};
  const filter = pickOut(phoneLayer.filter, id);

  if (phoneLayer.type !== 'line') {
    return {
      id: haloId(phoneLayer.id),
      type: 'circle',
      source: phoneLayer.source,
      filter,
      paint: {
        'circle-color': ['get', 'stroke'],
        'circle-radius': PLACE_RADIUS,
        'circle-blur': PLACE_BLUR,
        'circle-opacity': PLACE_OPACITY
      }
    };
  }

  const width = typeof paint['line-width'] === 'number' ? paint['line-width'] : DEFAULT_WIDTH;
  const halo = {
    id: haloId(phoneLayer.id),
    type: 'line',
    source: phoneLayer.source,
    filter,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      // The page's own paper rather than the feature's colour: the edge says "this is the line you picked",
      // and the line under it keeps saying when that line is due.
      'line-color': CASING_COLOUR,
      'line-width': width + CASING_WIDTH_EXTRA,
      'line-blur': CASING_BLUR,
      'line-opacity': CASING_OPACITY
    }
  };

  // The dash pattern, when the phone's layer has one - copied, not chosen: an edge is the line, under it.
  // Copied *scaled*, because a dasharray is in multiples of the line's own width and the edge is a wider
  // line: an edge twelve pixels wide wearing a five-pixel line's numbers would put its dashes two and a
  // half times further apart than the line's - in the gaps between them, so each dash would sit on bare
  // imagery and the edge would read as a speckle rather than as an edge.
  if (paint['line-dasharray']) {
    const scale = width / (width + CASING_WIDTH_EXTRA);
    halo.paint['line-dasharray'] = paint['line-dasharray'].map((length) => length * scale);
  }

  return halo;
}
