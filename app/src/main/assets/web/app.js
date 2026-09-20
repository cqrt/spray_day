/*
 * The desk: the phone's own work, drawn on a computer.
 *
 * It draws, and it changes one thing: the details of a track or a place, saved to the phone when the
 * operator presses Save. What those details may hold, what a refusal says, and what a block name
 * becomes are all decided on the phone, in Kotlin, by the same rules the app's own edit screen uses.
 * This page fills a form in and sends it.
 *
 * Everything it shows comes from the phone on the same Wi-Fi, and it shows it without deciding
 * anything itself: the style is the one the phone builds (the same layer ids, the same dashes, the
 * same houses), the features are the ones the phone's own map draws, a due colour is whatever colour
 * the phone painted that feature, and the form's options are the phone's own words. So there is one
 * place in the world that decides what "due soon" looks like and what a track may be called, and this
 * file is not it.
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
 * A save: one asset's details, sent to the phone.
 *
 * A refusal is not an exception here. A 400 and a 409 arrive with a document the phone wrote - the
 * words to show the operator, and whether the card is out of date - and both are things to be said
 * in those words rather than in a stack trace. What is thrown is a failure of the arrangement rather
 * than of the edit: no token, a refused address, or an answer that is not a document at all.
 */
async function putJson(path, body) {
  const response = await fetch(withToken(path), {
    method: 'PUT',
    // Both are served from the phone's own origin, so there is no preflight in the way; the content
    // type is here because the phone refuses a body it was not told was JSON.
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
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

/** The map's own source for the work, found from the phone's own layer names - see `assetsSourceId`. */
let workSource = null;

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

  // Where the work is drawn from, so a save can hand the map the phone's new features without asking
  // for the style again.
  workSource = assetsSourceId(style);

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

  // The way into the form. Assigned rather than added, because the card is redrawn on every save and
  // a listener added again on each one would save the asset twice.
  document.getElementById('card-edit').onclick = () => openEdit(item);
}

function closeCard() {
  document.getElementById('card').hidden = true;
  selectedId = null;
  for (const button of document.querySelectorAll('#list button')) {
    button.setAttribute('aria-current', 'false');
  }
}

/* ---- Changing the details of one track or place ------------------------------------- */

/** The asset the form is open on, or null when it is shut. */
let editingId = null;

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

  field('edit-title').textContent = `Change ${item.asset.name}`;
  field('edit-name').value = item.asset.name;
  field('edit-block').value = item.groupName || '';
  field('edit-interval').value = String(item.asset.intervalDays);
  field('edit-swath').value = item.asset.swathWidthM === null ? '' : String(item.asset.swathWidthM);
  field('edit-separation').value =
    item.asset.passSeparationM === null ? '' : String(item.asset.passSeparationM);
  field('edit-notes').value = item.asset.notes || '';

  fillChoices('edit-kind', state.choices.kinds, item.asset.kind);
  fillChoices('edit-shape', state.choices.shapes, item.asset.shape);
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

  showShapeRow();
  showSeparationRow();
  updateBlockHint();

  field('edit-words').hidden = true;
  field('edit').hidden = false;
  field('card').hidden = true;
  field('edit-name').focus();
}

/**
 * The shape is offered only for infrastructure, and moving to another kind puts it back to a line -
 * a picnic table's shape has no business sitting on a road. The phone's own rule: see
 * `AssetEditScreen`.
 */
function showShapeRow() {
  const kind = selectedValue('edit-kind');
  field('edit-shape-row').hidden = kind !== 'INFRASTRUCTURE';
  if (kind !== 'INFRASTRUCTURE') setSelected('edit-shape', 'LINE');
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
  field('edit').hidden = true;
  field('edit-words').hidden = true;
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
 * Saves the form to the phone.
 *
 * The phone judges every field - the ranges, the blank-means-unknown rules, the block name - and its
 * words are what the operator is shown, because they are the words the phone's own edit screen would
 * use. What this function decides is only what to do with the answer: a save closes the form and
 * redraws the work from what came back, a refusal the operator can fix leaves the form open with the
 * phone's sentence under it, and a refusal because the asset moved under the page closes the form
 * and reloads the lot, because the page's copy is the thing that is wrong.
 */
async function saveEdit(event) {
  event.preventDefault();
  if (editingId === null) return;

  const item = state.assets.find((one) => one.asset.id === editingId);
  if (!item) return;

  const body = {
    name: textOf('edit-name'),
    kind: selectedValue('edit-kind'),
    shape: selectedValue('edit-shape'),
    method: selectedValue('edit-method'),
    blockName: textOf('edit-block'),
    intervalDays: textOf('edit-interval'),
    swathWidthM: textOf('edit-swath'),
    passesRequired: Number(selectedValue('edit-passes')),
    passSeparationM: textOf('edit-separation'),
    notes: textOf('edit-notes'),
    // What the phone handed this card, so an edit written against an asset that has since changed is
    // refused rather than written over. See `WebEditorVersion` on the phone.
    version: item.version
  };

  const save = field('edit-save');
  save.disabled = true;
  try {
    const { status, answer } = await putJson(`/api/assets/${editingId}`, body);

    if (status !== 200) {
      showEditProblem(answer.message || 'The phone would not save that.');
      if (status === 409) {
        closeEdit();
        await reloadWork();
        showNotice(answer.message);
      }
      return;
    }

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
document.getElementById('edit-close').addEventListener('click', closeEdit);
document.getElementById('edit-cancel').addEventListener('click', closeEdit);
document.getElementById('edit-form').addEventListener('submit', saveEdit);
document.getElementById('edit-kind').addEventListener('change', showShapeRow);
document.getElementById('edit-passes').addEventListener('change', showSeparationRow);
document.getElementById('edit-method').addEventListener('change', onMethodChange);
document.getElementById('edit-block').addEventListener('input', updateBlockHint);

window.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  // Escape backs out of the form first: it is on top, and it is the one with unsaved typing in it.
  if (!field('edit').hidden) {
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
