package nz.mckenzie.sprayday.offline

import java.io.File

/** One aerial tile in the XYZ scheme. */
data class TileRef(val zoom: Int, val x: Int, val y: Int)

/**
 * Imagery tiles on disk, laid out as `{z}/{x}/{y}.webp` under a root directory.
 *
 * Deliberately plain files rather than MapLibre's offline database or an MBTiles
 * file: it is trivially inspectable, resumable tile-by-tile, safe to delete, and
 * it can be served straight back to the map by the local tile server.
 *
 * A directory of files also copes with the real world: LINZ has no imagery for
 * some tiles, and a missing file is simply a 404 rather than a hole in a
 * database that has to be reasoned about.
 *
 * The suffix follows the source: the aerial tiles are `.webp`, OpenStreetMap's are `.png`. One
 * store holds one source, so a store knows which it is - see
 * [nz.mckenzie.sprayday.domain.tiles.Basemap.tileSuffix].
 */
class OfflineTileStore(
    private val root: File,
    /** How this source's tiles are named on disk, e.g. `.webp`. */
    private val suffix: String = TILE_SUFFIX
) {

    fun tileFile(zoom: Int, x: Int, y: Int): File = File(root, "$zoom/$x/$y$suffix")

    fun contains(zoom: Int, x: Int, y: Int): Boolean = tileFile(zoom, x, y).isFile

    fun read(zoom: Int, x: Int, y: Int): ByteArray? {
        val file = tileFile(zoom, x, y)
        return if (file.isFile) file.readBytes() else null
    }

    /** Writes a tile, creating directories as needed. Returns the bytes stored. */
    fun write(zoom: Int, x: Int, y: Int, bytes: ByteArray): Int {
        val file = tileFile(zoom, x, y)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bytes.size
    }

    /** Tiles currently held, by walking the tree (used for reporting on launch). */
    fun storedTileCount(): Long = root.walkTopDown().count { it.isFile && it.name.endsWith(suffix) }.toLong()

    fun storedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes everything. Returns how many files were deleted. */
    fun deleteAll(): Int {
        val files = root.walkTopDown().filter { it.isFile }.count()
        root.deleteRecursively()
        return files
    }

    companion object {
        const val TILE_SUFFIX = ".webp"
    }
}
