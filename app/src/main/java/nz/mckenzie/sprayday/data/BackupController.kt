package nz.mckenzie.sprayday.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFileException
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSummary
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
 */
class BackupController(
    private val repository: BackupRepository,
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    /** "spray-day-backup-2026-09-14.json" - dated, so two backups do not collide. */
    fun suggestedFileName(nowEpochMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
        return "spray-day-backup-$date.json"
    }

    suspend fun exportTo(uri: Uri, nowEpochMs: Long = System.currentTimeMillis()): BackupSummary {
        val document = repository.export()
        writeText(uri, BackupFormat.encode(document))
        return BackupFormat.summarise(document)
    }

    /** What the file holds, without touching anything. */
    suspend fun inspect(uri: Uri): BackupSummary = BackupFormat.summarise(readDocument(uri))

    /** What the app holds right now, so the two can be compared before restoring. */
    suspend fun currentSummary(): BackupSummary = repository.currentSummary()

    suspend fun restoreFrom(uri: Uri): BackupSummary = repository.restore(readDocument(uri))

    private suspend fun readDocument(uri: Uri): BackupDocument = readText(uri).let(BackupFormat::decode)

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
