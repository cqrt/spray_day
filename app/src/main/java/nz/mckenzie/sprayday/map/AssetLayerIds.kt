package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetLayer

/**
 * Which style layer each layer of the work is drawn by.
 *
 * The map is built from four layers - one line layer per kind, because a dash pattern is a
 * constant in MapLibre rather than something a feature can carry, and one for places - and this
 * is the only place that says which of them a switch hides. A table rather than a `when` buried
 * in the map view, so that a switch and the layer it hides cannot drift apart without a test
 * noticing: every layer an operator can hide has a layer to hide, and no two share one.
 */
object AssetLayerIds {

    /** Solid lines: tracks. */
    const val TRACKS = "sprayday-assets-line-track"

    /** Dashed lines: roads. */
    const val ROADS = "sprayday-assets-line-road"

    /** Dotted lines: the infrastructure you travel along. */
    const val FENCELINES = "sprayday-assets-line-infrastructure"

    /** The houses: anything that is a single spot rather than a path. */
    const val PLACES = "sprayday-assets-point"

    /** The style layer [layer] is drawn by. */
    fun of(layer: AssetLayer): String = when (layer) {
        AssetLayer.TRACKS -> TRACKS
        AssetLayer.ROADS -> ROADS
        AssetLayer.FENCELINES -> FENCELINES
        AssetLayer.PLACES -> PLACES
    }
}
