package nz.mckenzie.sprayday.offline

/**
 * Which offline pack the map picker is choosing an area for.
 *
 * The two packs are independent caches - imagery tiles and DOC's tracks - so they get an area each,
 * chosen the same way: tap two corners, then download. The picker is one screen because the choosing
 * is one thing; only what is downloaded afterwards differs.
 */
enum class OfflinePickerMode {
    /** Basemap imagery tiles, for the map to work with no reception. */
    IMAGERY,

    /** The Department of Conservation's tracks, for the DOC browser to work with no reception. */
    DOC_TRACKS
}
