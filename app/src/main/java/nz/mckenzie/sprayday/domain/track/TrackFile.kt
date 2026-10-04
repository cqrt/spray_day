package nz.mckenzie.sprayday.domain.track

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * A track file's bytes turned into the XML they hold.
 *
 * KMZ is a KML document **zipped**, so a `.kmz` file is never text: reading its bytes as UTF-8 gives
 * the archive's own header rather than a track, which is why the unzipping is here, once, on the
 * phone, for the picker and the desk alike. What the unzipped KML then *means* is
 * [TrackInterchange]'s business, not this object's - this only decides which document in the archive
 * the track is in.
 *
 * Everything else - GPX, KML - is text already, and is merely decoded.
 */
object TrackFile {

    /** A ZIP's local file header: the first four bytes of any archive with at least one entry. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * How much KML this will read out of an archive.
     *
     * The compressed file is capped by the socket, but decompression can expand a small archive many
     * times over - a "zip bomb" is that on purpose - so the decompressed text is capped too, rather
     * than poured into memory until the phone gives up.
     */
    private const val MAX_KML_BYTES = 4 * 1024 * 1024

    /** Said when a `.kmz` opens but holds no KML document, in the operator's own words. */
    const val NO_KML_IN_ARCHIVE = "That .kmz file holds no KML document to read."

    /** Said when a `.kmz` cannot be opened at all - not a ZIP, or a broken one. */
    const val ARCHIVE_UNREADABLE =
        "That .kmz file could not be opened. Pick a GPX, KML or KMZ file and try again."

    /** Said when the KML inside a `.kmz` is larger than the phone will read. */
    const val KML_TOO_LARGE =
        "The KML inside that .kmz file is larger than the phone will read."

    /** Whether these bytes begin a ZIP archive - which is what a KMZ is. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    /**
     * The XML text of a track file.
     *
     * A zip is opened and its first `.kml` document read - the KMZ spec's own rule, whose usual name
     * for that document is `doc.kml`. Anything else is decoded as UTF-8, which is what every other
     * GPX and KML file is.
     */
    fun text(bytes: ByteArray): String =
        if (isZip(bytes)) kmlTextOf(bytes) else bytes.toString(Charsets.UTF_8)

    private fun kmlTextOf(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            try {
                var entry = zip.nextEntry
                while (entry != null) {
                    // The first KML in the archive, in the order the archive lists it: a KMZ's
                    // document is normally the only one, and normally doc.kml.
                    if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                        return bounded(zip)
                    }
                    entry = zip.nextEntry
                }
            } catch (broken: IOException) {
                // Not a readable archive after all - a corrupted download, or a file named .kmz that
                // is not one. The operator is told in words rather than shown a ZipException.
                throw IllegalArgumentException(ARCHIVE_UNREADABLE)
            }
        }
        throw IllegalArgumentException(NO_KML_IN_ARCHIVE)
    }

    /** The KML entry's text, refusing rather than reading past [MAX_KML_BYTES]. */
    private fun bounded(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_KML_BYTES) throw IllegalArgumentException(KML_TOO_LARGE)
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
