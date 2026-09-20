/*
 * The desk: the phone's own work, drawn on a computer.
 *
 * Everything this page shows comes from the phone on the same Wi-Fi, and it shows it without
 * deciding anything itself: the style is the one the phone builds (the same layer ids, the same
 * dashes, the same houses), the features are the ones the phone's own map draws, and a due colour
 * is whatever colour the phone painted that feature. So there is one place in the world that decides
 * what "due soon" looks like, and this file is not it.
 *
 * It reads. Nothing here writes, and phase 1 has no code that could: the endpoints it calls are
 * GETs.
 */

const params = new URLSearchParams(location.search);
const TOKEN = params.get('k') || '';

/** Everything the phone serves is behind the token, and the token is in the address. */
const withToken = (path) => `${path}?k=${encodeURIComponent(TOKEN)}`;

const notice = document.getElementById('notice');

/** One line of plain English, in the operator's terms, instead of a console nobody is looking at. */
function showNotice(text) {
  notice.textContent = text;
  notice.hidden = false;
}

async function getJson(path) {
  const response = await fetch(withToken(path));
  if (response.status === 403) {
    throw new Error('The phone refused that address. Turn the switch off and on again in Settings, ' +
      'then open the new address - the old one stops working when the phone stops serving.');
  }
  if (!response.ok) {
    throw new Error(`The phone answered ${response.status} for ${path}.`);
  }
  return response.json();
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


/** Farm words for the phone's own names, so the desk says what the app says. */
const KIND_TEXT = { TRACK: 'Track', ROAD: 'Road', INFRASTRUCTURE: 'Fenceline or stopbank' };
const METHOD_TEXT = { UNSET: 'Not set', BOOM: 'Boom', KNAPSACK: 'Knapsack' };

function kindText(kind) {
  return KIND_TEXT[kind] || kind.toLowerCase();
}

function methodText(method) {
  return METHOD_TEXT[method] || method.toLowerCase().replace(/_/g, ' ');
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

async function boot() {
  if (!TOKEN) {
    showNotice('There is no token in this address. Open the address from Settings on the phone - ' +
      'the token is part of it, and it is what the phone checks.');
    return;
  }

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

    map = new maplibregl.Map({
      container: 'map',
      style,
      // The credit the licences require, in view rather than behind a tap - the same as the phone.
      attributionControl: { compact: false }
    });
    map.addControl(new maplibregl.NavigationControl(), 'top-right');

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
  } catch (error) {
    showNotice(error.message);
  }
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

  // A click on any of the work's own layers opens that asset, whichever layer drew it.
  for (const layer of (style.layers || []).map((l) => l.id)) {
    if (!layer.startsWith('sprayday-assets-')) continue;
    map.on('click', layer, (event) => selectAsset(event.features[0].properties.id));
    map.on('mouseenter', layer, () => { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', layer, () => { map.getCanvas().style.cursor = ''; });
  }

  // Open on the work rather than on an ocean: the phone's own box. With nothing drawn yet the
  // style's own centre and zoom apply, which are the phone's map's own. Unless something has already
  // been picked out of the list: the imagery arrives after the list does, so a click that lands while
  // the map is still opening must not be undone by the map finishing.
  if (state.bounds && selectedId === null) {
    map.fitBounds(
      [[state.bounds.minLng, state.bounds.minLat], [state.bounds.maxLng, state.bounds.maxLat]],
      { padding: 70, duration: 0 }
    );
  }
  placePhone(state.position);
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

  if (!shown.length) {
    const empty = document.createElement('p');
    empty.className = 'where';
    empty.style.padding = '0 12px';
    empty.textContent = state.assets.length
      ? 'No track or block matches that.'
      : 'There is nothing on the farm yet. Draw a track on the phone and it appears here.';
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

function listRow(item) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.id = item.asset.id;
  button.setAttribute('aria-current', String(item.asset.id === selectedId));

  const dot = document.createElement('span');
  dot.className = 'dot';
  dot.style.background = strokeById.get(item.asset.id) || '#757575';

  const name = document.createElement('span');
  name.textContent = item.asset.name;

  const where = document.createElement('span');
  where.className = 'where';
  where.textContent = dueText(item);

  button.append(dot, name, where);
  button.addEventListener('click', () => selectAsset(item.asset.id, true));
  return button;
}

/** Opens an asset: the card, the row highlighted, and the map brought to it. */
function selectAsset(id, fromList = false) {
  const item = state.assets.find((a) => a.asset.id === id);
  if (!item) return;

  selectedId = id;
  showCard(item);
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
 * The card: what the asset is and when it is next due.
 *
 * Read-only, and it says so by having nothing on it to press - phase 2 is where this page starts
 * changing things, and it starts on the phone first.
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

  fact('What it is', item.asset.shape === 'POINT'
    ? `${kindText(item.asset.kind)}, a place`
    : kindText(item.asset.kind));
  fact('Block', item.groupName || OTHERS);
  fact('Length', item.asset.shape === 'LINE' ? metresText(item.asset.lengthM) : null);
  fact('Spray every', `${item.asset.intervalDays} days`);
  fact('Next due', dateText(item.dueAtEpochMs));
  fact('Last sprayed', dateText(item.asset.lastSprayedAtEpochMs) || 'never');
  fact('Sprays recorded', item.sprayCount ? String(item.sprayCount) : null);
  fact('Method', methodText(item.asset.method));
  fact('Swath', item.asset.swathWidthM ? `${item.asset.swathWidthM} m` : null);
  fact('Passes', item.asset.passesRequired > 1 ? String(item.asset.passesRequired) : null);
  fact('Notes', item.asset.notes);

  document.getElementById('card').hidden = false;
}

function closeCard() {
  document.getElementById('card').hidden = true;
  selectedId = null;
  for (const button of document.querySelectorAll('#list button')) {
    button.setAttribute('aria-current', 'false');
  }
}

document.getElementById('card-close').addEventListener('click', closeCard);
window.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closeCard();
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
