/*
 * The desk: the phone's own work, drawn and changed on a computer.
 *
 * It draws, and it writes four things: the details of a track or a place, the line a track follows, a
 * track that did not exist until somebody drew it, and - when there is nothing recorded against it - its
 * removal. What a write may hold, what a refusal says, and what a block name becomes are all decided on
 * the phone, in Kotlin, by the same rules the app's own screens use. This page fills a form in, draws a
 * line, and sends them.
 *
 * The drawing itself - the handles, the drags, the undo stack - is `edit.js`, with the arithmetic in
 * `geometry.mjs` so it can be tested without a browser. What is here is the page: the list, the card,
 * the form, and the four bodies a write can carry.
 *
 * Everything it shows comes from the phone on the same Wi-Fi, and it shows it without deciding anything
 * itself: the style is the one the phone builds (the same layer ids, the same dashes, the same houses),
 * the features are the ones the phone's own map draws, a due colour is whatever colour the phone painted
 * that feature, the form's options are the phone's own words, and whether a track may be deleted at all
 * is the phone's own answer. So there is one place in the world that decides what "due soon" looks like,
 * what a track may be called and what may go, and this file is not it.
 */

const params = new URLSearchParams(location.search);
const TOKEN = params.get('k') || '';

/**
 * The one piece of the page's own logic that decides anything about a write: which of the two drawing
 * shapes a save carries. Imported with the token on the URL, because a browser asks for a module with
 * no query unless it is told otherwise and the phone's gate answers nothing without one.
 */
const { drawingBody } = await import(
  TOKEN ? `./wire.mjs?k=${encodeURIComponent(TOKEN)}` : './wire.mjs'
);

/**
 * The glow under the picked-out track, and the one decision in the page's own drawing furniture that is
 * worth pinning: the halo takes the *phone's* layer's shape - its filter, its width, its dashes - so a
 * fenceline's glow is dotted. Imported the same way, for the same reason.
 */
const { NO_ASSET, haloId, haloLayer, pickOut } = await import(
  TOKEN ? `./glow.mjs?k=${encodeURIComponent(TOKEN)}` : './glow.mjs'
);

/**
 * Where the desk was looking the last time it was open.
 *
 * A refresh, and a basemap switch on the phone - which builds the editor's run again - both throw this page
 * away and build it again, and neither of them is a different desk: an operator working one corner of the
 * farm would otherwise lose it and have to find it again on every basemap. The camera is kept in the
 * browser's own store rather than on the phone, because where a particular desk is looking is the desk's
 * own business, and the plan's rule is that the desk's only write is an asset. Imported the same way as the
 * other two modules, for the same reason.
 */
const { cameraOf, openingCamera, recall, remember } = await import(
  TOKEN ? `./camera.mjs?k=${encodeURIComponent(TOKEN)}` : './camera.mjs'
);

/**
 * The page's words for the phone's codes: the phone's own labels, arrived in the state document.
 *
 * Imported the same way, and in a module for the same reason: what the card says an asset is has to be
 * the phone's own word for it, and a word kept here as well as on the phone is a word that falls behind
 * - which is what made a building read as "building, a place".
 */
const { kindText, methodText } = await import(
  TOKEN ? `./words.mjs?k=${encodeURIComponent(TOKEN)}` : './words.mjs'
);

/**
 * The browser's own store, or null when this browser will not hand one over.
 *
 * Reading `window.localStorage` is itself a thing that can throw - a browser with storage switched off
 * refuses the property rather than the method - so even asking for it is inside the guard, and the module
 * that uses it is written to work with null.
 */
function browserStore() {
  try {
    return window.localStorage;
  } catch (error) {
    return null;
  }
}

/**
 * Everything the phone serves is behind the token, and the token is in the address - except when the
 * operator has turned the token off on the phone, in which case the address has none and there is
 * nothing to add.
 *
 * A path that already carries a query - a delete, whose version travels in one - gets the token joined
 * onto it rather than asked for twice.
 */
const withToken = (path) => TOKEN
  ? `${path}${path.includes('?') ? '&' : '?'}k=${encodeURIComponent(TOKEN)}`
  : path;

const notice = document.getElementById('notice');

/** One line of plain English, in the operator's terms, instead of a console nobody is looking at. */
function showNotice(text) {
  notice.textContent = text;
  notice.hidden = false;
}

/** The one thing worth saying when the phone says no: the token, and how to get a fresh address. */
function tokenRefused() {
  return new Error('The phone refused that address. Turn the switch off and on again in Settings, ' +
    'then open the new address - the old one stops working when the phone stops serving.');
}

async function getJson(path) {
  const response = await fetch(withToken(path));
  if (response.status === 403) throw tokenRefused();
  if (!response.ok) {
    throw new Error(`The phone answered ${response.status} for ${path}.`);
  }
  return response.json();
}

/**
 * A write: one of the three the phone takes, sent with the token on its URL.
 *
 * A refusal is not an exception here. A 400, a 409 and a delete the phone will not take all arrive with
 * a document the phone wrote - the words to show the operator, and what kind of refusal it was - and all
 * are things to be said in those words rather than in a stack trace. What is thrown is a failure of the
 * arrangement rather than of the write: no token, a refused address, or an answer that is not a document
 * at all.
 */
async function sendJson(path, method, body = null) {
  const response = await fetch(withToken(path), {
    method,
    // Both are served from the phone's own origin, so there is no preflight in the way; the content type
    // is here because the phone refuses a body it was not told was JSON.
    ...(body === null
      ? {}
      : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  });
  if (response.status === 403) throw tokenRefused();
  const answer = await response.json().catch(() => null);
  if (!answer) throw new Error(`The phone answered ${response.status} for ${path}, and not with a document.`);
  return { status: response.status, answer };
}

/**
 * The houses a place is drawn as, painted here rather than shipped as pictures.
 *
 * The phone says which pictures it needs - the style carries `placeIcons`, and a place asks for one
 * by name - and the page paints them, so the APK carries no copies of a marker the phone already
 * knows how to draw. The shape is the phone's own house: a roof to a ridge, two walls, and a white
 * edge half outside the walls, which is why the house is built inside the square rather than on its
 * edge.
 */
const HOUSE_PX = 22;
const HOUSE_EAVE = 0.44;

function houseImage(colour) {
  const ratio = Math.max(1, Math.round(window.devicePixelRatio || 1));
  const size = HOUSE_PX * ratio;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');

  // The white edge, stroked fat and then filled over: a red house over dark winter imagery is
  // otherwise a dark shape on dark ground, which is exactly when somebody is looking for it.
  const edge = Math.max(2, Math.round(size * 0.09));
  const left = edge;
  const right = size - edge;
  const top = edge;
  const bottom = size - edge;
  const eave = top + (bottom - top) * HOUSE_EAVE;

  const path = () => {
    ctx.beginPath();
    ctx.moveTo((left + right) / 2, top);
    ctx.lineTo(right, eave);
    ctx.lineTo(right, bottom);
    ctx.lineTo(left, bottom);
    ctx.lineTo(left, eave);
    ctx.closePath();
  };

  path();
  ctx.lineJoin = 'round';
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = edge * 2;
  ctx.stroke();

  path();
  ctx.fillStyle = colour;
  ctx.fill();

  return { image: ctx.getImageData(0, 0, size, size), pixelRatio: ratio };
}

/** `#2e7d32` from the name the style asks for: the phone names a picture after its colour. */
function colourOfPlaceIcon(name) {
  const hex = name.replace('sprayday-place-', '');
  return `#${hex}`;
}


function metresText(metres) {
  if (!metres) return 'not measured';
  return metres >= 1000 ? `${(metres / 1000).toFixed(2)} km` : `${Math.round(metres)} m`;
}

function dateText(epochMs) {
  return epochMs ? new Date(epochMs).toLocaleDateString() : '';
}

/** When it is due, in the words an operator would use, from the phone's own arithmetic. */
function dueText({ dueStatus, daysUntilDue }) {
  if (dueStatus === 'NEVER_SPRAYED') return 'Never sprayed';
  if (dueStatus === 'OVERDUE') {
    return daysUntilDue === -1 ? 'One day overdue' : `${-daysUntilDue} days overdue`;
  }
  if (daysUntilDue === 0) return 'Due today';
  if (daysUntilDue === 1) return 'Due tomorrow';
  return `Due in ${daysUntilDue} days`;
}

let map;
let state;
let strokeById = new Map();
let phoneMarker = null;
let selectedId = null;

/**
 * Where this desk was looking when it was last open, or null when there is no memory of it.
 *
 * Held as well as handed to the map, because opening on the work's own box has to know whether the desk has
 * already been placed: a memory of a corner of the farm is not to be undone by the map finishing loading.
 */
let rememberedCamera = null;

/** The map's own source for the work, found from the phone's own layer names - see `assetsSourceId`. */
let workSource = null;

/** The page's own drawing: the handles, the drags, the keys. Null until the map has its style. */
let editor = null;

/**
 * The line drawn for a track that does not exist yet, and null when nothing is waiting.
 *
 * A new track is drawn first and described afterwards - lay the line, then say what it is - so the line
 * has to be held somewhere while the form is open, and the form's Cancel comes back to the drawing with
 * it intact: a line that took twenty clicks to lay is not something a change of mind about a name ought
 * to cost.
 */
let draftPaths = null;

async function boot() {
  // Nothing checks for a missing token here any more, and that is deliberate: the phone's gate hands
  // this page to a request that carried the token, or to a run that asks for none - so a page that
  // has loaded has already been let in. The 403 the documents below can still get is answered in the
  // phone's own words, which is where the operator should be told about it.
  try {
    // The three documents the page needs, asked for at once: the work, its features, and the style
    // the phone builds for it.
    const [work, features, style] = await Promise.all([
      getJson('/api/state'),
      getJson('/api/assets.geojson'),
      getJson('/api/style')
    ]);
    state = work;
    strokeById = new Map(features.features.map((f) => [f.properties.id, f.properties.stroke]));
    featuresById = new Map(features.features.map((f) => [f.properties.id, f]));

    // The list is drawn from the phone's answer, before and regardless of the map. The work is what
    // this page is for and the map is the picture of it, so a browser that cannot draw the map - an
    // old machine, WebGL switched off - still gets the list, the due dates and the card.
    renderList();

    // Where this desk was looking when it was last open, if anywhere. Handed to the map as it is built
    // rather than moved to afterwards: a map that opens on the style's own centre and is then jumped is two
    // views, and the first of them asks the phone for imagery nobody is going to look at.
    rememberedCamera = recall(browserStore());

    map = new maplibregl.Map({
      container: 'map',
      style,
      // The memory, when there is one; otherwise the style's own centre and zoom, which are the phone's
      // map's own - and the work's own box is fitted once the style is here (see `onStyleLoaded`).
      ...(rememberedCamera ? openingCamera(rememberedCamera) : {}),
      // The credit the licences require, in view rather than behind a tap - the same as the phone.
      attributionControl: { compact: false }
    });
    map.addControl(new maplibregl.NavigationControl(), 'top-right');

    // Where the desk is left is where the next visit opens. Written when the map stops moving rather than
    // on every frame of a drag, and once more as the page goes away - which is the write a refresh must not
    // miss, because a page that is being replaced gets no chance to say anything afterwards. A tab that is
    // switched away from and never comes back is the same moment, from the page's point of view.
    map.on('moveend', keepCameraSoon);
    window.addEventListener('pagehide', keepCameraNow);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') keepCameraNow();
    });

    // A map that fails quietly is a blank rectangle, and a phone with no imagery yet and a style the
    // browser refuses both look like that. So anything MapLibre refuses says so, in words.
    map.on('error', (event) => {
      const reason = event && event.error ? (event.error.message || event.error) : 'no reason given';
      showNotice(`The map could not be drawn: ${reason}`);
    });

    // A place is drawn in whatever colour the phone gave it, and the style names the picture after
    // that colour: so the house is painted when it is asked for, rather than drawn up front and
    // hoped over. A colour no place has worn yet, and the first frame of a map that has just opened,
    // both arrive here.
    map.on('styleimagemissing', (event) => {
      if (!event.id.startsWith('sprayday-place-')) return;
      const { image, pixelRatio } = houseImage(colourOfPlaceIcon(event.id));
      map.addImage(event.id, image, { pixelRatio });
    });

    map.on('load', () => onStyleLoaded(style));

    // The drawing module: the page's own code, asked for with the token on its URL for the reason the
    // stylesheet is - a browser requests a module with no query unless it is told otherwise, and every
    // request the phone answers needs the token. Asked for here rather than at the top of the file
    // because it is handed the map, which did not exist until a moment ago.
    const { createEditor } = await import(withToken('./edit.js'));
    editor = createEditor({
      map,
      neighboursOf: featuresExcept,
      onChange: showDrawing,
      onFinish: finishDrawing,
      onCancel: abandonDrawing
    });
    // A drawing needs somewhere to be drawn, so the way in stays shut until the style is here.
    field('draw').disabled = false;
  } catch (error) {
    showNotice(error.message);
  }
}

/**
 * The phone's own features for every asset but one: what a drawing snaps onto.
 *
 * The features themselves rather than their vertices, because reading `[lng, lat]` into `{lat, lng}` is
 * the drawing module's business and is tested there without a browser - and turning coordinates round
 * twice is how a track drawn at the estuary ends up in the Southern Alps.
 */
function featuresExcept(exceptId) {
  return [...featuresById.values()].filter((feature) => feature.properties.id !== exceptId);
}

function onStyleLoaded(style) {
  // Which pictures exist is the phone's decision, in the style it built, so those are painted before
  // the first frame asks for them. The handler above is the net under this: a colour the phone has
  // never listed, or a frame that arrives first, still gets its house.
  for (const name of style.placeIcons || []) {
    if (!map.hasImage(name)) {
      const { image, pixelRatio } = houseImage(colourOfPlaceIcon(name));
      map.addImage(name, image, { pixelRatio });
    }
  }

  // Where the work is drawn from, so a save can hand the map the phone's new features without asking
  // for the style again.
  workSource = assetsSourceId(style);

  // The glow under the work, and the one asset already picked out - a row can be clicked before the
  // imagery arrives, and that click must not be lost by the style finishing afterwards.
  addGlowLayers(style);
  glowSelected();

  // A click on any of the work's own layers opens that asset, whichever layer drew it. While a line is
  // being drawn a click belongs to the drawing instead - to a handle, to a segment, or to the paddock
  // at the end of a new line - so the drawing gets it first.
  for (const layer of (style.layers || []).map((l) => l.id)) {
    if (!layer.startsWith('sprayday-assets-')) continue;
    map.on('click', layer, (event) => {
      if (editor && editor.isActive()) return;
      selectAsset(event.features[0].properties.id);
    });
    map.on('mouseenter', layer, () => {
      if (editor && editor.isActive()) return;
      map.getCanvas().style.cursor = 'pointer';
    });
    map.on('mouseleave', layer, () => {
      if (editor && editor.isActive()) return;
      map.getCanvas().style.cursor = '';
    });
  }

  // Open on the work rather than on an ocean: the phone's own box. With nothing drawn yet the
  // style's own centre and zoom apply, which are the phone's map's own. Unless something has already
  // been picked out of the list - the imagery arrives after the list does, so a click that lands while
  // the map is still opening must not be undone by the map finishing - or this desk has a memory of its
  // own view, in which case the map was already built looking at it and there is nothing to fit.
  if (state.bounds && selectedId === null && !rememberedCamera) {
    map.fitBounds(
      [[state.bounds.minLng, state.bounds.minLat], [state.bounds.maxLng, state.bounds.maxLat]],
      { padding: 70, duration: 0 }
    );
  }
  placePhone(state.position);
}

/*
 * Where the desk is looking, kept for the next time this page is built.
 *
 * The page is thrown away and rebuilt by a refresh and by a basemap switch on the phone, and neither of
 * those is a different desk - so the camera goes into the browser's own store as it is left and comes back
 * as the map's opening options. What may be kept, and what may be believed, is `camera.mjs` and is tested
 * without a browser; what is here is *when*, which is the part that needs a map.
 */

/** How long after the map stops moving the camera is written: a drag is one move, not two hundred. */
const CAMERA_SETTLE_MS = 400;

/** The write that has not happened yet, so a drag does not leave a queue of them behind it. */
let cameraWrite = null;

/** The camera as it is now, written once the map has stopped moving. */
function keepCameraSoon() {
  clearTimeout(cameraWrite);
  cameraWrite = setTimeout(keepCameraNow, CAMERA_SETTLE_MS);
}

/** The camera as it is now, written at once - for the page going away, which will not wait for a timer. */
function keepCameraNow() {
  clearTimeout(cameraWrite);
  if (map) remember(browserStore(), cameraOf(map));
}

/**
 * The map's own source for the work.
 *
 * Found from the phone's own layer names rather than written down here: the phone names every layer
 * of its work `sprayday-assets-...` (`AssetLayerIds`), each of those layers says which source it
 * draws, and that is the source a save hands the phone's new features to.
 */
function assetsSourceId(style) {
  const layer = (style.layers || []).find((one) => one.id.startsWith('sprayday-assets-'));
  return layer ? layer.source : null;
}

/* ---- The glow under the picked-out track -------------------------------------------- */

/*
 * The halo the desk draws under its own work: the asset that is picked out, in the phone's own colour for
 * it, wide and soft, so that a click says *which* of forty tracks the card is about.
 *
 * What the halo *is* - its shape, its width, its dashes, its colour - is `glow.mjs`, which is pure and
 * tested under node. What is here is only the part that needs a map: one halo layer per layer of the
 * phone's work, added once the style is there, and re-narrowed to one asset every time the selection
 * changes.
 */

/** The halo layers the page added, and the phone's own filter each one narrows. */
let glowFilters = new Map();

/**
 * One halo per layer of the phone's work, added once the style is there.
 *
 * Under all of the work rather than over it, because a halo is a light and not a lid: the phone's own line
 * sits on top of its own glow, at full strength, with its dashes unbroken.
 */
function addGlowLayers(style) {
  const phoneLayers = (style.layers || []).filter((one) => one.id.startsWith('sprayday-assets-'));
  if (!phoneLayers.length) return;

  glowFilters = new Map();
  const underEverything = phoneLayers[0].id;
  for (const phone of phoneLayers) {
    const id = haloId(phone.id);
    if (map.getLayer(id)) continue;
    glowFilters.set(id, phone.filter);
    map.addLayer(haloLayer(phone, NO_ASSET), underEverything);
  }
}

/** Puts the halo on the asset that is picked out and takes it off everything else. */
function glowSelected() {
  for (const [layer, phoneFilter] of glowFilters) {
    if (map.getLayer(layer)) map.setFilter(layer, pickOut(phoneFilter, selectedId ?? NO_ASSET));
  }
}

/** The phone's own dot, so "where is the phone?" has something to look at. */
function placePhone(position) {
  if (!position || !map) return;
  if (phoneMarker) {
    phoneMarker.setLngLat([position.lng, position.lat]);
    return;
  }
  const dot = document.createElement('div');
  dot.className = 'phone-dot';
  phoneMarker = new maplibregl.Marker({ element: dot })
    .setLngLat([position.lng, position.lat])
    .addTo(map);
}


let featuresById = new Map();

/**
 * The work as a list, grouped by block.
 *
 * A block is how the work is talked about on a farm - "the estuary block" is a road, a lagoon and
 * three tracks - and forty names in one column is not a plan. What an asset is due is said in words
 * beside its name, and its colour is the colour the phone painted it: taken from the feature the map
 * is drawing, rather than from a second copy of the palette in this file.
 *
 * A row is read across: the name, how it is sprayed, how many passes that takes, and when it is next
 * due. The method and the passes are the two the desk used to keep behind a card, and they are in the
 * list because "which of these is a knapsack job, and which takes two passes" is a question asked of the
 * whole list rather than of one track at a time - the two things that decide how the day is planned.
 */
function renderList() {
  document.getElementById('search').addEventListener('input', drawList);
  drawList();
}

const OTHERS = 'On their own';

function drawList() {
  const list = document.getElementById('list');
  const needle = document.getElementById('search').value.trim().toLowerCase();
  const shown = state.assets.filter(
    (item) => !needle || item.asset.name.toLowerCase().includes(needle)
  );

  list.textContent = '';

  // The words over the columns go with the work: a column of "Boom" and a column of "2" say nothing at
  // all without the word over each, and an empty list is not a table to be given headings.
  document.getElementById('list-head').hidden = !shown.length;

  if (!shown.length) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = state.assets.length
      ? 'No asset or block matches that.'
      : 'There is nothing on the farm yet. Draw one here, or on the phone.';
    list.append(empty);
    return;
  }

  const blocks = new Map();
  for (const item of shown) {
    const block = item.groupName || OTHERS;
    if (!blocks.has(block)) blocks.set(block, []);
    blocks.get(block).push(item);
  }

  const names = [...blocks.keys()].sort((a, b) => a.localeCompare(b));
  const others = names.indexOf(OTHERS);
  if (others >= 0) {
    names.splice(others, 1);
    names.push(OTHERS);
  }

  for (const block of names) {
    const heading = document.createElement('h3');
    heading.textContent = block;
    list.append(heading);

    const rows = blocks.get(block).sort((a, b) => a.asset.name.localeCompare(b.asset.name));
    for (const item of rows) list.append(listRow(item));
  }
}

/**
 * One row of the list: the name, how it is sprayed, how many passes that takes, and when it is next due.
 *
 * Four cells in the order the words over the list name them, and nothing else: the page keeps no second
 * list of its columns, so a cell cannot drift away from the heading it belongs under. The name carries
 * the dot the phone painted this asset's own feature, which is the row's colour and nothing else's.
 */
function listRow(item) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.id = item.asset.id;
  button.setAttribute('aria-current', String(item.asset.id === selectedId));

  const name = document.createElement('span');
  name.className = 'name';

  const dot = document.createElement('span');
  dot.className = 'dot';
  dot.style.background = strokeById.get(item.asset.id) || '#757575';

  name.append(dot, item.asset.name);

  const method = document.createElement('span');
  method.className = 'method';
  method.textContent = methodText(item.asset.method, state?.choices?.methods);

  const passes = document.createElement('span');
  passes.className = 'passes';
  // The number on its own: the word over the column says what it is, and what two passes *mean* - how far
  // apart they run, which is what the app's side-reading rests on - is the phone's own sentence on the card.
  passes.textContent = String(item.asset.passesRequired);

  const due = document.createElement('span');
  due.className = 'due';
  due.textContent = dueText(item);

  button.append(name, method, passes, due);
  button.addEventListener('click', () => selectAsset(item.asset.id, true));
  return button;
}

/** Opens an asset: the card, the row highlighted, the track glowing on the map, and the map brought to it. */
function selectAsset(id, fromList = false) {
  const item = state.assets.find((a) => a.asset.id === id);
  if (!item) return;

  selectedId = id;
  showCard(item);
  glowSelected();
  for (const button of document.querySelectorAll('#list button')) {
    button.setAttribute('aria-current', String(Number(button.dataset.id) === id));
  }

  if (fromList) flyToAsset(id);
}

function flyToAsset(id) {
  const feature = featuresById.get(id);
  const geometry = feature && feature.geometry;
  if (!geometry || !map) return;

  const points = geometry.type === 'Point' ? [geometry.coordinates] : geometry.coordinates;
  const lngs = points.map((p) => p[0]);
  const lats = points.map((p) => p[1]);
  map.fitBounds(
    [[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]],
    { padding: 90, maxZoom: 17, duration: 600 }
  );
}

/**
 * The card: what the asset is and when it is next due, and the way into changing its details.
 *
 * Everything on it is the phone's own: the due date and the count of sprays are worked out on the
 * phone, so a card that says "3 sprays" says what the phone would say.
 */
function showCard(item) {
  document.getElementById('card-name').textContent = item.asset.name;
  document.getElementById('card-due').textContent = dueText(item);

  const facts = document.getElementById('card-facts');
  facts.textContent = '';
  const fact = (label, value) => {
    if (value === null || value === undefined || value === '') return;
    const dt = document.createElement('dt');
    dt.textContent = label;
    const dd = document.createElement('dd');
    dd.textContent = value;
    facts.append(dt, dd);
  };

  // What it is, in one answer. The card used to add ", a place" for a spot, which is the *shape's* word
  // and not the kind's - and since the kind decides the shape, that was one question answered twice:
  // "Other place" read as "Other place, a place", and a building as "building, a place".
  fact('What it is', kindText(item.asset.kind, state?.choices?.kinds));
  fact('Block', item.groupName || OTHERS);
  fact('Length', item.asset.shape === 'LINE' ? metresText(item.asset.lengthM) : null);
  fact('Spray every', `${item.asset.intervalDays} days`);
  fact('Next due', dateText(item.dueAtEpochMs));
  fact('Last sprayed', dateText(item.asset.lastSprayedAtEpochMs) || 'never');
  fact('Sprays recorded', item.sprayCount ? String(item.sprayCount) : null);
  fact('Method', methodText(item.asset.method, state?.choices?.methods));
  fact('Swath', item.asset.swathWidthM ? `${item.asset.swathWidthM} m` : null);
  fact('Passes', item.asset.passesRequired > 1 ? String(item.asset.passesRequired) : null);
  fact('Notes', item.asset.notes);

  document.getElementById('card').hidden = false;

  // The ways in from the card. Assigned rather than added, because the card is redrawn on every save and
  // a listener added again on each one would save the asset twice.
  document.getElementById('card-edit').onclick = () => openEdit(item);
  document.getElementById('card-shape').onclick = () => startShape(item);

  // Deleting: the phone's own sentence about what is on the track, and its own answer about whether the
  // desk may take it away at all. A track with a spray on it is not something this page can offer to
  // delete - not because the page has a rule, but because the phone would refuse it, and saying so before
  // the button is pressed is better than saying so after.
  field('card-remove-words').textContent = item.removal.sentence;
  const remove = field('card-remove');
  remove.disabled = !item.removal.allowed;
  remove.onclick = () => deleteAsset(item);
}

/**
 * Tidying a track: its own line and side tracks become the desk's drawing.
 *
 * The **paths** the phone handed over are what is opened, not the feature the map is drawing: a feature
 * is one path per asset, and a line that is changed from here has to hand every path back - a spur is not
 * put back by a page that never held it.
 */
function startShape(item) {
  if (!editor) return;
  closeCard();
  editor.startOn(item.asset.id, item.paths ?? []);
}

/* ---- Drawing a track, saving a line, taking one away -------------------------------- */

/**
 * What the box in the map's corner says while a line is being drawn.
 *
 * The page's own words, because none of this is a rule - it is how the map is worked. What the phone
 * will or will not take is the phone's to say, and it says it when the drawing is saved.
 *
 * Three things, in the order they are read: what the job is (the phone's own title for the same job on
 * the same line), what there is so far (the phone's own counts), one short line about whatever the
 * drawing is in the middle of, and then the keys as **pairs** - a key and three or four words - rather
 * than the run of sentences this used to be. A box in the corner of a map is read at a glance; a bar
 * across the top of a page is read once and then in the way.
 *
 * The two side-track buttons are shown by the drawing's own state rather than by a mode of their own:
 * *Side track* is offered while the line has two vertices to hang one off and none is being drawn, and
 * *Back to the track* and *Remove this side track* while one is. That is the same shape the phone's own
 * screen has, so the same gesture means the same thing in both places.
 */
function showDrawing({ mode, paths, sideTracks, sideTrackInHand, lengthM, junction, canUndo, tracing }) {
  const box = field('drawing');
  if (mode === 'off') {
    box.hidden = true;
    return;
  }

  // The phone's own titles for the two jobs, because they are the same two jobs here.
  field('drawing-title').textContent = mode === 'new' ? 'Draw a line' : 'Change the line';

  const count = paths.reduce((total, path) => total + path.length, 0);
  // The counts in the phone's own words, and the length only once there is a line to measure: one point
  // is not a line, and "not measured" is the card's word for a place, not this.
  const parts = [`${count} point${count === 1 ? '' : 's'}`];
  if (count > 1) {
    parts.push(metresText(lengthM));
  }
  if (sideTracks > 0) {
    parts.push(`${sideTracks} side track${sideTracks === 1 ? '' : 's'}`);
  }
  field('drawing-info').textContent = parts.join(' · ');

  // One line, and only when there is one worth saying. Following the pointer comes first because it is
  // what the hand is doing at that moment: the whole fence lands at once, so letting go is not a
  // commitment to twenty vertices. Then which path the handles belong to, which is what decides what a
  // drag and Del do, and where a side track would leave from - a picked point is easy to miss on a map
  // full of fences, and it is the one thing that decides where the spur starts.
  const state = tracing
    ? 'Following the pointer - let go to put it down'
    : sideTrackInHand
      ? 'Working on the side track'
      : junction
        ? 'A side track will leave the line here'
        : (sideTracks > 0 ? 'Click a side track to work on it' : '');
  const words = field('drawing-state');
  words.textContent = state;
  words.hidden = !state;

  // A new line is finished by naming it; a line the phone already has is saved as it stands.
  field('drawing-enter').textContent = mode === 'new'
    ? 'finish (or double click)'
    : 'save (or double click)';
  field('drawing-keys').classList.toggle('no-undo', !canUndo);

  // The side-track furniture, in the same states the drawing is in. *Remove this side track* is offered for
  // any side track in hand, finished or not, because that is the other half of being able to edit one.
  const line = paths[0] ?? [];
  field('drawing-side-track').hidden = sideTrackInHand || line.length < 2;
  field('drawing-back').hidden = !sideTrackInHand;
  field('drawing-drop').hidden = !sideTrackInHand;
  box.hidden = false;
}

/**
 * A finished drawing: a track the phone already has is saved; a new one is described first.
 *
 * Both end with the drawing inside a write, but only one of them can be written straight away. An asset
 * already on the phone has a name and an interval and only its paths have moved, while a new line is
 * nothing but its shape - so the form comes first and the drawing waits in `draftPaths`.
 */
async function finishDrawing(paths, wasEditing) {
  if (wasEditing !== null) {
    await savePath(wasEditing, paths);
    return;
  }
  draftPaths = paths;
  openDraft();
}

/**
 * The operator has given up on a line.
 *
 * Nothing has to be put back, which is the whole reason this is cheap: the phone was never asked to
 * change anything, so for an asset it already has the provisional line is simply hidden, and for a new
 * track the draft goes with it.
 */
function abandonDrawing() {
  draftPaths = null;
}

/**
 * Saves a line the operator has finished drawing.
 *
 * The body is the row the card is showing with the new line in it, which is what the phone wants: one
 * write judged as a whole, so a moved vertex and the rest of the row cannot land without each other.
 * The card's own version travels with it, so a track that has been drawn again on the phone is refused
 * rather than overwritten - the phone's line wins and the page reloads to show it.
 */
async function savePath(assetId, paths) {
  const item = state.assets.find((one) => one.asset.id === assetId);
  if (!item) return;

  try {
    const { status, answer } = await sendJson(`/api/assets/${assetId}`, 'PUT', recordBody(item, paths));
    if (status !== 200) {
      showNotice(answer.message || 'The phone would not save that line.');
      if (status === 409 && answer.reason === 'stale') await reloadWork();
      return;
    }
    replaceAsset(answer.asset);
    await reloadFeatures();
    drawList();
    showCard(answer.asset);
    showNotice('Saved. The phone has it.');
  } catch (error) {
    showNotice(error.message);
  }
}

/**
 * Takes a track away, when there is nothing recorded against it.
 *
 * The phone decides that and not this page: the sentence on the card is the phone's, the button is off
 * when the phone said no, and the answer to the request is the phone's too. A mis-drawn track goes; one
 * with history on it is refused, with the numbers in the sentence, and the operator is sent to the phone
 * where the sprays can be seen going with it.
 */
async function deleteAsset(item) {
  const button = field('card-remove');
  button.disabled = true;
  try {
    const { status, answer } = await sendJson(
      `/api/assets/${item.asset.id}?version=${encodeURIComponent(item.version)}`,
      'DELETE'
    );
    if (status !== 200) {
      showNotice(answer.message || 'The phone would not delete it.');
      // Either 409 means the page's own copy is the thing that is wrong - the row moved under it, or a
      // spray arrived that the card had never heard of - so the work is read again rather than argued with.
      if (status === 409) await reloadWork();
      return;
    }
    closeCard();
    await reloadWork();
    showNotice(answer.message);
  } catch (error) {
    showNotice(error.message);
  } finally {
    button.disabled = false;
  }
}

/**
 * The body the form sends: what the operator typed, and the version the card was handed.
 *
 * `version` and `points` are left out entirely when they do not apply, rather than sent empty: a body
 * that says nothing about the line leaves the line alone, which is what the details form means - and a
 * new track has no version to quote, because there is no row for one to describe.
 */
function formBody({ version = null, points = null, paths = null } = {}) {
  const body = {
    name: textOf('edit-name'),
    kind: selectedValue('edit-kind'),
    method: selectedValue('edit-method'),
    blockName: textOf('edit-block'),
    intervalDays: textOf('edit-interval'),
    swathWidthM: textOf('edit-swath'),
    passesRequired: Number(selectedValue('edit-passes')),
    passSeparationM: textOf('edit-separation'),
    notes: textOf('edit-notes')
  };
  if (version !== null) body.version = version;
  if (points !== null) body.points = points;
  if (paths !== null) body.paths = paths;
  return body;
}

/**
 * The body a line's save sends: the row the card is showing, with the drawn line in it.
 *
 * Built from the record rather than from the form's fields, because the form is not open - and the record
 * is the row the phone last handed over, so what goes back is what the operator is looking at.
 *
 * Which of the two drawing shapes it carries - one path, or the line with the track's side tracks - is
 * `wire.mjs`'s decision, so that it can be tested without a browser.
 */
function recordBody(item, paths) {
  return {
    name: item.asset.name,
    kind: item.asset.kind,
    shape: item.asset.shape,
    method: item.asset.method,
    blockName: item.groupName || '',
    intervalDays: String(item.asset.intervalDays),
    swathWidthM: item.asset.swathWidthM === null ? '' : String(item.asset.swathWidthM),
    passesRequired: item.asset.passesRequired,
    passSeparationM: item.asset.passSeparationM === null ? '' : String(item.asset.passSeparationM),
    notes: item.asset.notes || '',
    version: item.version,
    ...drawingBody(paths)
  };
}

function closeCard() {
  document.getElementById('card').hidden = true;
  selectedId = null;
  glowSelected();
  for (const button of document.querySelectorAll('#list button')) {
    button.setAttribute('aria-current', 'false');
  }
}

/* ---- Changing the details of one track or place ------------------------------------- */

/** The asset the form is open on, or null when it is shut. */
let editingId = null;

/**
 * Whether the form is describing a track that does not exist yet.
 *
 * A new track and an edit are the same form and two different writes - a POST to the collection with the
 * drawn line, or a PUT of one asset with a version quoted back - so the flag is what tells them apart at
 * the moment of the save, which is the only place they differ.
 */
let drafting = false;

/** The swath width the method in the form came with, so a change of method can move the field. */
let methodSwath = '';

const field = (id) => document.getElementById(id);
const textOf = (id) => field(id).value.trim();

/** The value of a radio row: the choice the phone's own vocabulary named. */
function selectedValue(id) {
  const chosen = document.querySelector(`#${id} input:checked`);
  return chosen ? chosen.value : '';
}

function setSelected(id, value) {
  for (const input of document.querySelectorAll(`#${id} input`)) {
    input.checked = input.value === value;
  }
}

/**
 * One choice row, from the phone's own vocabulary and worded with the phone's own labels.
 *
 * Built every time the form opens, because the vocabulary can change under it: a kind added to the
 * phone is in the next document the desk is handed, and the row follows with nothing to change here.
 */
function fillChoices(id, choices, chosen) {
  const row = field(id);
  row.textContent = '';
  for (const choice of choices) {
    const label = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'radio';
    input.name = id;
    input.value = choice.value;
    input.checked = choice.value === chosen;
    label.append(input, document.createTextNode(choice.label));
    row.append(label);
  }
}

/** Opens the form on an asset, filled with what the phone says it holds. */
function openEdit(item) {
  editingId = item.asset.id;
  drafting = false;

  field('edit-title').textContent = `Edit ${item.asset.name}`;
  field('edit-name').value = item.asset.name;
  field('edit-block').value = item.groupName || '';
  field('edit-interval').value = String(item.asset.intervalDays);
  field('edit-swath').value = item.asset.swathWidthM === null ? '' : String(item.asset.swathWidthM);
  field('edit-separation').value =
    item.asset.passSeparationM === null ? '' : String(item.asset.passSeparationM);
  field('edit-notes').value = item.asset.notes || '';

  fillChoices('edit-kind', state.choices.kinds, item.asset.kind);
  fillChoices('edit-method', state.choices.methods, item.asset.method);
  fillChoices('edit-passes', state.choices.passes, String(item.asset.passesRequired));

  const method = state.choices.methods.find((one) => one.value === item.asset.method);
  methodSwath = (method && method.swathM) || '';

  field('edit-swath-hint').textContent = state.choices.swathHint;
  field('edit-separation-hint').textContent = state.choices.separationHint;

  // The blocks that exist, so a name can be picked rather than typed again: a typed block is how a
  // misspelling becomes a second block with one asset in it.
  const blocks = field('edit-blocks');
  blocks.textContent = '';
  for (const block of state.groups) {
    const option = document.createElement('option');
    option.value = block.name;
    blocks.append(option);
  }

  showSeparationRow();
  updateBlockHint();

  field('edit-words').hidden = true;
  field('edit').hidden = false;
  field('card').hidden = true;
  field('edit-name').focus();
}

/**
 * One pass has nothing to be apart from, so the separation field goes with it.
 *
 * The number would be cleared by the phone anyway (`AssetEdits` keeps no separation for a one-pass
 * job); clearing it here means the operator is not shown a field that would be thrown away.
 */
function showSeparationRow() {
  const two = Number(selectedValue('edit-passes')) >= 2;
  field('edit-separation-row').hidden = !two;
  if (!two) field('edit-separation').value = '';
}

/**
 * What the block field is about to do, in the phone's own words.
 *
 * The rule is `AssetEdits.blockHint`'s, written out here because it depends on every keystroke - a
 * sentence fetched from the phone could be no fresher than the last request. What it decides is a
 * hint and nothing else: the block is resolved by the phone, in the same transaction as the save,
 * and a name that matches nothing yet starts one.
 */
function updateBlockHint() {
  const typed = textOf('edit-block');
  const hint = field('edit-block-hint');
  if (!typed) {
    hint.textContent = state.choices.blockHint;
    return;
  }
  const match = state.groups.find((block) => block.name.toLowerCase() === typed.toLowerCase());
  hint.textContent = match
    ? `In the block "${match.name}"`
    : `Starts a new block called "${typed}"`;
}

/**
 * A change of spray method moves the swath field when the width in it is nobody's own.
 *
 * `AssetEdits.swathAfterMethodChange`'s rule, with the widths out of the phone's own table: a blank
 * field, or one still holding the previous method's default, moves with the choice; a width the
 * operator typed does not. Without this, choosing "Knapsack" here would leave a boom's three metres
 * on a backpack.
 */
function onMethodChange() {
  const chosen = state.choices.methods.find((one) => one.value === selectedValue('edit-method'));
  const width = field('edit-swath');
  const current = width.value.trim();
  if (current === '' || current === methodSwath) width.value = (chosen && chosen.swathM) || '';
  methodSwath = (chosen && chosen.swathM) || '';
}

function closeEdit() {
  editingId = null;
  drafting = false;
  field('edit').hidden = true;
  field('edit-words').hidden = true;
}

/**
 * The form for a new track, opened over the line just drawn.
 *
 * Prefilled with the phone's own starting point for an asset - a track, following a path, one pass, the
 * default interval - because those are the answers the phone would fill in for itself. Everything else
 * here is the same form the card opens: the same fields, the same choices, the same rules underneath, and
 * the same sentences when the phone refuses something.
 */
function openDraft() {
  editingId = null;
  drafting = true;

  field('edit-title').textContent = 'New asset';
  field('edit-name').value = '';
  field('edit-block').value = '';
  field('edit-interval').value = state.newAsset.intervalDays;
  field('edit-swath').value = '';
  field('edit-separation').value = '';
  field('edit-notes').value = '';

  fillChoices('edit-kind', state.choices.kinds, state.newAsset.kind);
  fillChoices('edit-method', state.choices.methods, state.newAsset.method);
  fillChoices('edit-passes', state.choices.passes, String(state.newAsset.passesRequired));

  // No swath width of its own for a method nobody has set, which is the phone's own table saying so.
  const method = state.choices.methods.find((one) => one.value === state.newAsset.method);
  methodSwath = (method && method.swathM) || '';

  field('edit-swath-hint').textContent = state.choices.swathHint;
  field('edit-separation-hint').textContent = state.choices.separationHint;

  const blocks = field('edit-blocks');
  blocks.textContent = '';
  for (const block of state.groups) {
    const option = document.createElement('option');
    option.value = block.name;
    blocks.append(option);
  }

  showSeparationRow();
  updateBlockHint();

  field('edit-words').hidden = true;
  field('edit').hidden = false;
  field('card').hidden = true;
  field('edit-name').focus();
}

/**
 * Backing out of the form.
 *
 * For a new track that means back to the drawing rather than back to nothing: the drawing is the
 * operator's work, and a change of mind about the name is not a reason to lose it. The paths go back to
 * the map, so finishing again is one key away - and giving up altogether is the Escape that follows.
 */
function cancelForm() {
  const paths = drafting ? draftPaths : null;
  closeEdit();
  if (paths && editor) {
    draftPaths = null;
    editor.startNew(paths);
  }
}

/** The phone's own words about what it refused, where the operator is looking when it happens. */
function showEditProblem(message) {
  const words = field('edit-words');
  words.textContent = message;
  words.hidden = false;
}

/** The record the phone sent back, in place of the one the page was holding. */
function replaceAsset(record) {
  const at = state.assets.findIndex((one) => one.asset.id === record.asset.id);
  if (at >= 0) state.assets[at] = record; else state.assets.push(record);
}

/**
 * The work's features, re-read and handed to the map.
 *
 * A save can move a due date, and a due date is a colour, so the map's own source is given the
 * phone's new features rather than being left drawing the picture it had a moment ago.
 */
async function reloadFeatures() {
  const features = await getJson('/api/assets.geojson');
  strokeById = new Map(features.features.map((f) => [f.properties.id, f.properties.stroke]));
  featuresById = new Map(features.features.map((f) => [f.properties.id, f]));
  if (map && workSource && map.getSource(workSource)) map.getSource(workSource).setData(features);
}

/** The work as the phone holds it now: the document, the features, the list and the card. */
async function reloadWork() {
  state = await getJson('/api/state');
  await reloadFeatures();
  drawList();
  const item = state.assets.find((one) => one.asset.id === selectedId);
  if (item) showCard(item);
}

/**
 * Saves the form to the phone: the details of a track, or a track that did not exist until now.
 *
 * The phone judges every field - the ranges, the blank-means-unknown rules, the block name, and the drawn
 * line as well - and its words are what the operator is shown, because they are the words the phone's own
 * screen would use. What this function decides is only what to do with the answer: a save closes the form
 * and redraws the work from what came back, a refusal the operator can fix leaves the form open with the
 * phone's sentence under it, and a refusal because the asset moved under the page closes the form and
 * reloads the lot, because the page's copy is the thing that is wrong.
 *
 * The two writes it can make are one line apart: a new track is a POST to the collection with the line
 * that was drawn, and everything else is a PUT of one asset with the version its card was handed.
 */
async function saveEdit(event) {
  event.preventDefault();

  const item = drafting ? null : state.assets.find((one) => one.asset.id === editingId);
  if (!drafting && !item) return;

  const body = drafting ? formBody({ paths: draftPaths }) : formBody({ version: item.version });
  const path = drafting ? '/api/assets' : `/api/assets/${editingId}`;

  const save = field('edit-save');
  save.disabled = true;
  try {
    const { status, answer } = await sendJson(path, drafting ? 'POST' : 'PUT', body);

    // 201 for a new track and 200 for a changed one: both are the phone saying it has it.
    if (status !== 200 && status !== 201) {
      showEditProblem(answer.message || 'The phone would not save that.');
      if (status === 409) {
        closeEdit();
        await reloadWork();
        showNotice(answer.message);
      }
      return;
    }

    // The line is the phone's now, so nothing is waiting on it any more.
    draftPaths = null;
    replaceAsset(answer.asset);
    closeEdit();
    await reloadFeatures();
    drawList();
    showCard(answer.asset);
    showNotice('Saved. The phone has it.');
  } catch (error) {
    showEditProblem(error.message);
  } finally {
    save.disabled = false;
  }
}

document.getElementById('card-close').addEventListener('click', closeCard);
document.getElementById('edit-close').addEventListener('click', cancelForm);
document.getElementById('edit-cancel').addEventListener('click', cancelForm);
document.getElementById('edit-form').addEventListener('submit', saveEdit);
document.getElementById('edit-passes').addEventListener('change', showSeparationRow);
document.getElementById('edit-method').addEventListener('change', onMethodChange);
document.getElementById('edit-block').addEventListener('input', updateBlockHint);

/** The way into drawing a track: an empty line, with nothing on the map to take hold of yet. */
document.getElementById('draw').addEventListener('click', () => {
  if (!editor || editor.isActive()) return;
  closeCard();
  draftPaths = null;
  editor.startNew();
});

// The side-track furniture on the drawing bar. Each of these is a move the drawing makes on itself -
// nothing here talks to the phone - so the page's buttons and the editor's own keys lead to the same
// place, which is why neither is wired to the other.
document.getElementById('drawing-side-track').addEventListener('click', () => {
  if (editor) editor.startSideTrack();
});
document.getElementById('drawing-back').addEventListener('click', () => {
  if (editor) editor.backToLine();
});
document.getElementById('drawing-drop').addEventListener('click', () => {
  if (editor) editor.dropSideTrack();
});

window.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  // Escape backs out of the form first: it is on top, and it is the one with unsaved typing in it. While a
  // line is being drawn the drawing has the key itself (see `edit.js`), so this only runs when the form is
  // open - which is what makes two Escapes in a row mean "back to the drawing, then give it up".
  if (!field('edit').hidden) {
    if (drafting) {
      cancelForm();
      return;
    }
    const item = state.assets.find((one) => one.asset.id === selectedId);
    closeEdit();
    if (item) showCard(item);
    return;
  }
  closeCard();
});



/**
 * "Where is the phone?" asks the phone, rather than the browser.
 *
 * A browser on plain `http://` refuses a page its location, and the phone's fix is the one the
 * operator means anyway: the phone is the thing in the paddock. Asking for the state document again
 * is what makes this the phone's *newest* fix rather than the one from when the page was opened.
 */
document.getElementById('locate').addEventListener('click', async () => {
  try {
    const fresh = await getJson('/api/state');
    state.position = fresh.position;
    if (!state.position) {
      showNotice('The phone does not know where it is yet. Open the Map tab on the phone for a ' +
        'moment and try again.');
      return;
    }
    notice.hidden = true;
    placePhone(state.position);
    if (map) {
      map.flyTo({
        center: [state.position.lng, state.position.lat],
        zoom: Math.max(map.getZoom(), 15)
      });
    }
  } catch (error) {
    showNotice(error.message);
  }
});

boot();
