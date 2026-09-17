package nz.mckenzie.sprayday.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFileException
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSettingsRecord
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.BackupSwitches
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Backup files at the edges: the file the operator picks, and the text in it.
 *
 * The operator chooses the destination (a Downloads folder, a USB stick, an email
 * attachment) through the system file picker, so this deals in `Uri`s. Inspecting is
 * separate from restoring on purpose: the screen shows what a file holds, and what
 * the app holds, before anything is replaced.
 *
 * Text rather than a file is what the destinations above this actually exchange - an
 * off-site backup hands the same text to a repository instead of a `Uri` - so the encoding,
 * the decoding and the counters live here once, and no destination gets its own idea of what
 * a backup is.
 */
class BackupController(
    private val repository: BackupRepository,
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    /**
     * The switches a backup carries to a new phone. Null when there are none to carry,
     * which is how the tests and the handover path have always used this.
     */
    private val switches: BackupSwitches? = null
) {

    /** "spray-day-backup-2026-09-14.json" - dated, so two backups do not collide. */
    fun suggestedFileName(nowEpochMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
        return "spray-day-backup-$date.json"
    }

    suspend fun exportTo(uri: Uri, nowEpochMs: Long = System.currentTimeMillis()): BackupSummary {
        val document = exportDocument()
        writeText(uri, BackupFormat.encode(document))
        return BackupFormat.summarise(document)
    }

    /** The backup as text, with the switches in it, for any destination to keep. */
    suspend fun exportText(): String = BackupFormat.encode(exportDocument())

    /** What the file holds, without touching anything. */
    suspend fun inspect(uri: Uri): BackupSummary = inspectText(readText(uri))

    /** What text holds, without touching anything. */
    suspend fun inspectText(text: String): BackupSummary = BackupFormat.summarise(BackupFormat.decode(text))

    /** What the app holds right now, so the two can be compared before restoring. */
    suspend fun currentSummary(): BackupSummary = repository.currentSummary()

    suspend fun restoreFrom(uri: Uri): BackupSummary = restoreText(readText(uri))

    /**
     * Replaces everything with what the text holds.
     *
     * The switches come back with the data on purpose: a phone restored from a copy should
     * come back set up, not factory-fresh. Credentials do not travel this way - see
     * [nz.mckenzie.sprayday.domain.backup.BackupSettingsRecord].
     */
    suspend fun restoreText(text: String): BackupSummary {
        val document = BackupFormat.decode(text)
        val summary = repository.restore(document)
        document.settings?.let { record -> switches?.apply(record) }
        return summary
    }

    private suspend fun exportDocument(): BackupDocument = repository.export(switches?.read())

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw BackupFileException("That file could not be opened.")
    }

    private suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFileException("That file could not be written.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
