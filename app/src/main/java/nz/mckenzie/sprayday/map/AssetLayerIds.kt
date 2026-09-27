package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.asset.AssetShape

/**
 * Which style layer each layer of the work is drawn by.
 *
 * The map is built from a layer per line kind, three for the ground that has an edge - its surface, the
 * white rim round it, and the boundary between the two - and a marker layer per place kind, because a
 * dash pattern is a constant in MapLibre rather than something a feature can carry, and a kind can only
 * be hidden on its own if it is drawn on its own. This is the only place that says which of them a
 * switch hides. A table rather than a `when` buried in the map view, so that a switch and
 * the layer it hides cannot drift apart without a test noticing: every layer an operator can hide has
 * a layer to hide, and no two share one.
 */
object AssetLayerIds {

    /** Solid lines: tracks. */
    const val TRACKS = "sprayday-assets-line-track"

    /** Dashed lines: roads. */
    const val ROADS = "sprayday-assets-line-road"

    /** Dotted lines: the infrastructure you travel along. */
    const val FENCELINES = "sprayday-assets-line-infrastructure"

    /**
     * The boundary of a piece of ground: a carpark, drawn solid round its own shape.
     *
     * The one layer whose features are rings rather than paths - its filter asks for `AREA` - which is
     * why it is named for the shape rather than for the line it happens to be drawn with.
     */
    const val CARPARKS = "sprayday-assets-area-carpark"

    /**
     * The white rim *outside* that boundary: a line wider than the boundary, drawn under it.
     *
     * The boundary is drawn in the traffic light's own colour, which is what says when the ground is
     * due - so the ground needs something that says *where* it is, over dark winter imagery and over
     * the pale gravel a carpark usually sits on. White is the one colour the light never uses, which is
     * the same argument the desk's in-hand edge is made of, so a white rim never says anything about
     * when the work is due.
     *
     * It is drawn *under* the boundary rather than beside it, two pixels wider: one pixel of white
     * shows outside the ring and the sliver inside it is behind the boundary, so the boundary's own
     * colour is never narrowed and the rim never becomes a second edge to read.
     */
    const val CARPARKS_CASING = "sprayday-assets-area-carpark-casing"

    /**
     * The ground itself: the surface inside that boundary.
     *
     * A boundary on its own still reads as a fence, which is what was reported - a carpark is ground,
     * and ground is a surface rather than an outline. It is the first of the three layers the ground's
     * one switch hides - the surface, the rim outside it, the boundary between the two - and it is
     * drawn under every line so a track across a carpark is drawn on the ground rather than behind it.
     */
    const val CARPARKS_FILL = "sprayday-assets-area-carpark-fill"

    /**
     * The marker layer a [kind] of place is drawn by.
     *
     * Named from the kind rather than listed, so a kind added to the app cannot arrive without a
     * layer to be drawn in - and the five names cannot drift from the five kinds.
     */
    fun pointOf(kind: AssetKind): String =
        POINT_PREFIX + kind.name.lowercase().replace('_', '-')

    /**
     * The style layer [layer] is drawn by - its one layer, except for the ground, whose boundary this
     * names and whose fill is [CARPARKS_FILL] and its rim [CARPARKS_CASING]. See [idsOf] for what a
     * switch hides.
     */
    fun of(layer: AssetLayer): String = when (layer.kind) {
        AssetKind.TRACK -> TRACKS
        AssetKind.ROAD -> ROADS
        AssetKind.FENCELINE -> FENCELINES
        AssetKind.CARPARK -> CARPARKS
        else -> pointOf(layer.kind)
    }

    /**
     * Every style layer [layer] is drawn by.
     *
     * One for every layer of the work but the ground with an edge, which is three: a surface, the white
     * rim outside it, and the boundary between the two. A switch hides what this returns rather than
     * [of] alone, so switching *Carparks* off cannot leave the ground behind and switching it on cannot
     * show a fill with no edge.
     */
    fun idsOf(layer: AssetLayer): List<String> =
        if (layer.kind == AssetKind.CARPARK) {
            listOf(CARPARKS_FILL, CARPARKS_CASING, CARPARKS)
        } else {
            listOf(of(layer))
        }

    /**
     * Every layer the map draws, in the order the style builds and draws them.
     *
     * The ground's fill comes first because everything else is drawn on top of it, its rim comes
     * straight after it and still under every line, and the boundary comes after the other lines
     * because a boundary is what the ground ends at - and because the rim is drawn *under* it, which is
     * what leaves only the outside pixel of that rim showing.
     */
    val ALL: List<String> =
        listOf(CARPARKS_FILL, CARPARKS_CASING, TRACKS, ROADS, FENCELINES, CARPARKS) +
            PlaceIcons.KINDS.map { pointOf(it) }

    private const val POINT_PREFIX = "sprayday-assets-point-"
}
