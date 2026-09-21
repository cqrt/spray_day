/*
 * Drawing on the desk: the handles, the drags, the clicks and the keys.
 *
 * This is the half of the drawing that needs a map and a DOM. Everything it decides with arithmetic -
 * which vertex is under the cursor, where a click on the line goes, what a snapped vertex becomes, and
 * the whole of the undo stack - is in `geometry.mjs`, which node can run, and is tested there without a
 * browser. What is left here is the part that cannot be: attaching to MapLibre's events, painting the
 * line and its handles with the page's own furniture, and telling the page what to say in its bar.
 *
 * **The module is imported with the token on the end of its URL**, because every request the phone
 * answers needs one and a browser asks for an `import` with no query unless it is told otherwise - the
 * same trap `index.html` documents for its own stylesheet. So this file finds the token in the address
 * the operator opened, exactly as `app.js` does, and asks for `./geometry.mjs?k=...` - or for the module
 * with no query at all when the phone is serving with no token, which is the other kind of run.
 *
 * **Nothing here talks to the phone.** A vertex is moved in the page's own memory, undone from the
 * page's own history, and the finished line is handed to `onFinish` - which is the page's business,
 * and is where a save or a new track's form happens. An afternoon of tidying a track is one write.
 */

const TOKEN = new URLSearchParams(location.search).get('k') || '';

const geometry = await import(
  TOKEN ? `./geometry.mjs?k=${encodeURIComponent(TOKEN)}` : './geometry.mjs'
);

const {
  add,
  canRedo,
  canUndo,
  createPath,
  insert,
  move,
  redo,
  remove,
  samePlace,
  segmentAt,
  snap,
  toFeature,
  undo,
  vertexAt,
  verticesOf
} = geometry;

/** The page's own drawing furniture, deliberately not one of the due colours or a kind's. */
const INK = '#212121';
const PAPER = '#ffffff';

const SOURCE_ID = 'sprayday-desk-drawing';
const LINE_LAYER = 'sprayday-desk-drawing-line';
const HANDLE_LAYER = 'sprayday-desk-drawing-handles';

/**
 * The drawing, as a thing the page can start and stop.
 *
 * `onChange` is told what the line looks like now, so the page's bar can say how far along it is and
 * which keys do what; `onFinish` is handed the finished line and decides what happens next, because
 * whether that means a save or a form is the page's business and not the map's.
 */
export function createEditor({ map, onFinish, onCancel, onChange, neighboursOf }) {
  /** Off, drawing a new line, or editing one that is already on the phone. */
  let mode = 'off';

  /** The asset being edited, when the mode is `edit`; null for a new line, and when off. */
  let editingId = null;

  let path = createPath();
  let dragging = null;
  let hovered = -1;

  /** Where the cursor is in screen terms while a handle is being dragged. */
  let cursor = null;

  const project = (point) => map.project([point.lng, point.lat]);

  /**
   * Where a map event happened, on the ground and on the screen.
   *
   * MapLibre's own mouse events carry the position both ways - the screen `point`, and the `lngLat` it was
   * already worked out from - and a vertex is a place on the ground, so the ground is what is read here.
   * The screen point is then this page's own projection of that place, which keeps the two in step and
   * means a position is worked out once rather than twice: asking the map to unproject the `point` it just
   * handed over is a round trip that can only disagree with itself.
   */
  function placeOf(event) {
    const ground = { lat: event.lngLat.lat, lng: event.lngLat.lng };
    return { ground, screen: project(ground) };
  }

  /** A ground position, snapped onto whatever is near it, or left exactly where it is. */
  function spot(ground) {
    return snap(ground, candidates(), project) || ground;
  }

  /**
   * The vertices worth snapping onto: the other assets', and this line's own first.
   *
   * `neighboursOf` hands over the phone's own features - the ones the map is already drawing - and they
   * are read here, by the module that knows GeoJSON is lng-first, so no coordinate is turned round
   * twice. A line of two or more offers its own first vertex as well, which is how a track that comes
   * back to where it started is closed exactly rather than nearly.
   */
  function candidates() {
    const others = neighboursOf(editingId).flatMap(verticesOf);
    return path.points.length >= 2 ? [...others, path.points[0]] : others;
  }

  /** The line as it stands, with a drag in progress drawn where the cursor has taken it. */
  function pointsNow() {
    if (dragging === null || cursor === null) return path.points;
    const points = path.points.slice();
    points[dragging] = spot(cursor);
    return points;
  }

  function paint() {
    const source = map.getSource(SOURCE_ID);
    if (source) source.setData(toFeature(pointsNow()));
    onChange({ mode, points: pointsNow(), canUndo: canUndo(path), canRedo: canRedo(path), hovered });
  }

  /** Adds the page's own source and layers, once, and shows or hides them from then on. */
  function ready() {
    if (!map.getSource(SOURCE_ID)) {
      map.addSource(SOURCE_ID, { type: 'geojson', data: toFeature([]) });
      map.addLayer({
        id: LINE_LAYER,
        type: 'line',
        source: SOURCE_ID,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        // Dashed, so the line being drawn is never mistaken for one the phone is already drawing in a
        // due colour: this is the page's own pencil, not the farm's state.
        paint: { 'line-color': INK, 'line-width': 2, 'line-dasharray': [2, 2] }
      });
      map.addLayer({
        id: HANDLE_LAYER,
        type: 'circle',
        source: SOURCE_ID,
        paint: {
          'circle-radius': 6,
          'circle-color': PAPER,
          'circle-stroke-color': INK,
          'circle-stroke-width': 2
        }
      });
    }
    show(true);
  }

  /** Shows or hides the page's own line and handles, without taking them out of the style. */
  function show(visible) {
    for (const layer of [LINE_LAYER, HANDLE_LAYER]) {
      if (map.getLayer(layer)) {
        map.setLayoutProperty(layer, 'visibility', visible ? 'visible' : 'none');
      }
    }
  }

  /* ---- What the map tells us --------------------------------------------------------- */

  map.on('mousedown', (event) => {
    if (mode === 'off') return;
    const place = placeOf(event);
    const at = vertexAt(path.points, place.screen, project);
    if (at < 0) return;
    // A handle dragged is not the map panned, so the pan is stopped here and put back on mouseup - the
    // first moment at which MapLibre will let go of it.
    map.dragPan.disable();
    dragging = at;
    hovered = at;
    cursor = place.ground;
    paint();
  });

  map.on('mousemove', (event) => {
    if (mode === 'off') return;
    const place = placeOf(event);
    cursor = place.ground;
    if (dragging !== null) {
      paint();
      return;
    }
    const at = vertexAt(path.points, place.screen, project);
    if (at !== hovered) {
      hovered = at;
      paint();
    }
    map.getCanvas().style.cursor = at >= 0 ? 'grab' : 'crosshair';
  });

  map.on('mouseup', () => {
    if (mode === 'off' || dragging === null) return;
    const index = dragging;
    const point = spot(cursor);
    dragging = null;
    cursor = null;
    map.dragPan.enable();
    path = move(path, index, point);
    paint();
  });

  map.on('click', (event) => {
    if (mode === 'off') return;
    const place = placeOf(event);

    // A click on the line splits it: the vertex goes in between the two it was clicked between.
    const at = segmentAt(path.points, place.screen, project);
    if (at >= 0) {
      path = insert(path, at, spot(place.ground));
      paint();
      return;
    }

    // A click on the paddock adds to the end of a line still being drawn, and does nothing to one the
    // phone already has: a track that exists is changed by its handles, not by stray clicks.
    if (mode !== 'new') return;
    const end = path.points[path.points.length - 1];
    // A click on the spot the line already ends at adds nothing. That is exactly what the second half of
    // a double-click is, and a repeated vertex would only be collapsed again by the phone.
    if (end && samePlace(end, place.ground)) return;
    path = add(path, spot(place.ground));
    paint();
  });

  map.on('dblclick', (event) => {
    if (mode === 'off') return;
    // The double-click zoom is off while drawing, so this key is the only way to finish with a mouse.
    if (event.preventDefault) event.preventDefault();
    finish();
  });

  document.addEventListener('keydown', (event) => {
    if (mode === 'off') return;
    // Not while the operator is typing: the form has its own Escape, and a Delete key pressed in the
    // notes field means the notes.
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;

    const key = event.key.toLowerCase();
    const command = event.ctrlKey || event.metaKey;
    if (command && key === 'z') {
      event.preventDefault();
      path = event.shiftKey ? redo(path) : undo(path);
      paint();
      return;
    }
    if (command && key === 'y') {
      event.preventDefault();
      path = redo(path);
      paint();
      return;
    }
    if (event.key === 'Enter') {
      event.preventDefault();
      finish();
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      cancel();
      return;
    }
    if (event.key === 'Delete' || event.key === 'Backspace') {
      // The one the operator last had hold of, or the end of a line still being drawn.
      const which = hovered >= 0 ? hovered : path.points.length - 1;
      if (which >= 0) {
        event.preventDefault();
        path = remove(path, which);
        hovered = -1;
        paint();
      }
    }
  });

  /* ---- Starting, finishing, giving up ------------------------------------------------ */

  function start(nextMode, id, points) {
    ready();
    mode = nextMode;
    editingId = id;
    path = createPath(points);
    dragging = null;
    hovered = -1;
    // While a line is being drawn a double click finishes it, so the map's own double-click zoom is
    // out of the way - and put back the moment the drawing stops.
    map.doubleClickZoom.disable();
    paint();
  }

  function stop() {
    mode = 'off';
    editingId = null;
    dragging = null;
    hovered = -1;
    map.dragPan.enable();
    map.doubleClickZoom.enable();
    map.getCanvas().style.cursor = '';
    show(false);
    onChange({ mode: 'off', points: [], canUndo: false, canRedo: false, hovered: -1 });
  }

  /**
   * The operator is done: the line goes to the page, which decides what that means.
   *
   * A new track has a form to fill in; a track already on the phone has a save to make. Either way the
   * drawing stops here, and this is the only place a finished line leaves the map - which is why the
   * page, and not this file, is where the phone is reached for.
   */
  function finish() {
    if (mode === 'off') return;
    const finished = path.points;
    const wasEditing = editingId;
    stop();
    onFinish(finished, wasEditing);
  }

  /**
   * The operator has given up on this drawing, and everything is as it was.
   *
   * For a new track that is the end of it. For an asset the phone already has, the page's provisional
   * line is simply hidden: the phone was never asked to change anything, so there is nothing to put
   * back - which is what keeps Escape free of a write.
   */
  function cancel() {
    stop();
    onCancel();
  }

  return {
    /**
     * A new line, starting empty: it snaps onto every asset the farm already has.
     *
     * Points can be handed in, which is how the form's Cancel comes back to a drawing that was already
     * made rather than to a blank map - the line is the operator's work, and a form opened over it is
     * not a reason to lose it.
     */
    startNew: (points = []) => start('new', null, points),

    /** An asset's own line, out of the feature the map is drawing for it, ready to be tidied. */
    startOn: (assetId, feature) => start('edit', assetId, verticesOf(feature)),

    /** Whether a drawing is in progress: what the page asks before a map click means "open that". */
    isActive: () => mode !== 'off',

    /** The finished line, as the phone would store it - the page's own body carries it. */
    points: () => path.points,

    finish,
    cancel
  };
}
