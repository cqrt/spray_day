package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.asset.AssetShape

/**
 * Which style layer each layer of the work is drawn by.
 *
 * The map is built from eight layers - one line layer per line kind, because a dash pattern is a
 * constant in MapLibre rather than something a feature can carry, and one marker layer per place kind,
 * because that is what lets a kind be hidden on its own - and this is the only place that says which
 * of them a switch hides. A table rather than a `when` buried in the map view, so that a switch and
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
     * The marker layer a [kind] of place is drawn by.
     *
     * Named from the kind rather than listed, so a kind added to the app cannot arrive without a
     * layer to be drawn in - and the five names cannot drift from the five kinds.
     */
    fun pointOf(kind: AssetKind): String =
        POINT_PREFIX + kind.name.lowercase().replace('_', '-')

    /** The style layer [layer] is drawn by. */
    fun of(layer: AssetLayer): String = when (layer.kind) {
        AssetKind.TRACK -> TRACKS
        AssetKind.ROAD -> ROADS
        AssetKind.FENCELINE -> FENCELINES
        else -> pointOf(layer.kind)
    }

    /** Every layer the map draws, in the order the style builds and draws them. */
    val ALL: List<String> = listOf(TRACKS, ROADS, FENCELINES) + PlaceIcons.KINDS.map { pointOf(it) }

    private const val POINT_PREFIX = "sprayday-assets-point-"
}
