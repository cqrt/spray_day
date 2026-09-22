/*
 * The glow under the track that is picked out.
 *
 * Pure, and separate from `app.js` for the same reason `wire.mjs` is: node can run it without a browser
 * (`app/src/test/js/glow.test.mjs`, which CI runs), so what the desk's halo is made of is pinned by a test
 * rather than reviewed by eye.
 *
 * **The phone's shape, the page's glow.** The desk has a selection and the phone's own map has none, so
 * the halo is a layer of the page's - but it is built *out of one of the phone's own layers*: the same
 * source, the same filter (narrowed to the one asset), the same width, and the same dash pattern. The dash
 * pattern is the part worth having in a file a test can read: a halo that filled in the gaps between a
 * fenceline's dots would say "solid" about a fenceline, which is the one thing those dots are there to
 * say. So what the halo is the *shape* of stays the phone's decision, and the glow - how much wider, how
 * soft, how faint - is the page's, which is these three numbers.
 */

/** No asset picked out: an id no asset has, which is how a filter says "draw nothing". */
export const NO_ASSET = -1;

/** Wider than the line by this much in total, blurred by this, and this faint - the glow, in pixels. */
const GLOW_WIDTH_EXTRA = 7;
const GLOW_BLUR = 5;
const GLOW_OPACITY = 0.55;

/**
 * A place is a picture rather than a line, so its halo is a soft disc behind the house rather than a
 * wider line: there is no width to widen, and the picture is drawn on top of what is here. The disc is
 * bigger than the house by enough to be seen round it - a house is about twenty pixels across - and the
 * blur is mild, because a disc blurred all the way to nothing is hidden *by* the picture it is behind.
 */
const GLOW_PLACE_RADIUS = 22;
const GLOW_PLACE_BLUR = 0.5;
const GLOW_PLACE_OPACITY = 0.6;

/** The width a halo falls back to when the phone's own layer does not say one. */
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
 * The halo that belongs under one of the phone's layers: its shape, narrowed to the picked-out asset, and
 * the glow's own paint.
 *
 * Nothing glows until something is picked out: with no id the filter names an id no asset has, and the
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
        'circle-radius': GLOW_PLACE_RADIUS,
        'circle-blur': GLOW_PLACE_BLUR,
        'circle-opacity': GLOW_PLACE_OPACITY
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
      // The colour is the feature's own, so a halo can never say something about a track's state that the
      // line drawn on top of it does not already say.
      'line-color': ['get', 'stroke'],
      'line-width': width + GLOW_WIDTH_EXTRA,
      'line-blur': GLOW_BLUR,
      'line-opacity': GLOW_OPACITY
    }
  };

  // The dash pattern, when the phone's layer has one - copied, not chosen: a halo is the line blurred
  // rather than a band drawn through it. Copied *scaled*, because a dasharray is in multiples of the line's
  // own width and the halo is a wider line: a halo twelve pixels wide wearing a five-pixel line's numbers
  // would put its dots two and a half times further apart than the line's - in the gaps between them, so
  // each dot would sit on bare imagery and the glow would read as a speckle rather than as a halo.
  if (paint['line-dasharray']) {
    const scale = width / (width + GLOW_WIDTH_EXTRA);
    halo.paint['line-dasharray'] = paint['line-dasharray'].map((length) => length * scale);
  }

  return halo;
}
