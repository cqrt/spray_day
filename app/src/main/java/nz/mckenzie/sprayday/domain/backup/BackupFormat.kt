package nz.mckenzie.sprayday.domain.backup

import kotlinx.serialization.json.Json

/** A backup file that cannot be used, with an explanation worth showing. */
class BackupFileException(message: String) : Exception(message)

/**
 * Reading and writing the backup file.
 *
 * Decoding is strict about two things and forgiving about everything else: the file
 * has to say it is ours, and it has to have been written by a version this build
 * understands. Field-level differences are absorbed by defaults, so a file written by
 * an older build still restores - which is the direction that matters, since the
 * backup is the thing that has to outlive the app.
 */
object BackupFormat {

    /**
     * Pretty-printed on purpose: a backup is a file a person may open to see whether
     * their season is really in there.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(document: BackupDocument): String = json.encodeToString(document)

    fun decode(text: String): BackupDocument {
        val document = try {
            json.decodeFromString<BackupDocument>(text)
        } catch (failure: Exception) {
            throw BackupFileException(
                "That file is not a Spray Day backup: ${failure.message ?: "it could not be read"}"
            )
        }

        if (document.format != BackupDocument.FORMAT) {
            throw BackupFileException("That file is not a Spray Day backup.")
        }
        if (document.version > BackupDocument.VERSION) {
            throw BackupFileException(
                "That backup was written by a newer version of Spray Day " +
                    "(format ${document.version}), so this build cannot read it."
            )
        }

        return document
    }

    /**
     * The counts shown before and after a restore. The operator is about to replace
     * everything in the app, so they get to see both sides of it in numbers.
     */
    fun summarise(document: BackupDocument): BackupSummary = BackupSummary(
        assets = document.assets.size,
        sprays = document.sprayEvents.size,
        recordings = document.recordings.size,
        products = document.products.size,
        points = document.assets.sumOf { it.points.size } + document.recordings.sumOf { it.points.size }
    )
}

/** What a backup file, or the app itself, holds. */
data class BackupSummary(
    val assets: Int,
    val sprays: Int,
    val recordings: Int,
    val products: Int,
    val points: Int
) {
    val isEmpty: Boolean get() = assets == 0 && sprays == 0 && recordings == 0 && products == 0

    /** "2 assets, 3 sprays, 1 recording, 4 products" - for a person to check against. */
    fun describe(): String = listOf(
        count(assets, "asset"),
        count(sprays, "spray"),
        count(recordings, "recording"),
        count(products, "product")
    ).joinToString(", ")

    private fun count(value: Int, noun: String): String = "$value $noun" + if (value == 1) "" else "s"
}
