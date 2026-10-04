package nz.mckenzie.sprayday.domain.doc

/**
 * The DOC tracks downloaded for offline use.
 *
 * A **cache**, not the record of the farm: it holds tracks DOC published, so the browser can offer
 * them with no reception. The phone's own assets and their sprays are untouched by it, and clearing
 * it costs nothing but the download. That is why it lives beside the offline imagery rather than in
 * a backup.
 */
interface DocTrackCache {

    /** Everything downloaded, for the browser to search by name when the service cannot be reached. */
    suspend fun cached(): List<DocTrack>

    /** How many are downloaded, for the offline screen to say. */
    suspend fun count(): Int

    /** Adds or replaces tracks by their service key - a later download of the same track wins. */
    suspend fun store(tracks: List<DocTrack>)

    /** Forgets every downloaded track. */
    suspend fun clear()
}

/** A cache with nothing in it: where no store is wired in, and in tests. */
object EmptyDocTrackCache : DocTrackCache {
    override suspend fun cached(): List<DocTrack> = emptyList()

    override suspend fun count(): Int = 0

    override suspend fun store(tracks: List<DocTrack>) = Unit

    override suspend fun clear() = Unit
}

/**
 * The downloaded tracks whose name contains [nameContains], case-insensitively.
 *
 * All of them when the words are blank, because a search with no name is "what have I downloaded?".
 * A cache holds only the area the operator asked for, so no place filter is applied here: the download
 * is the place filter.
 */
fun List<DocTrack>.matching(nameContains: String): List<DocTrack> {
    val name = nameContains.trim()
    if (name.isEmpty()) return this
    return filter { it.name.contains(name, ignoreCase = true) }
}
