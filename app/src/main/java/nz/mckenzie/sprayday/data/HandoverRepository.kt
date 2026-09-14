package nz.mckenzie.sprayday.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.backup.BackupFileException
import nz.mckenzie.sprayday.domain.handover.HandoverCsv
import nz.mckenzie.sprayday.domain.handover.HandoverRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The season as something to hand to somebody else.
 *
 * Every spray, one row per product, in the order they happened: what was sprayed,
 * where, with what and how much, and which recording proves it. The CSV itself is
 * built in [HandoverCsv], which is pure and tested; this only has to fetch the rows
 * and put the file where the operator asked for it.
 */
class HandoverRepository(
    private val db: SprayDayDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    suspend fun rows(): List<HandoverRow> = db.sprayEventDao().handoverRows()

    fun renderCsv(rows: List<HandoverRow>): String = HandoverCsv.render(rows, zoneId)
}

/** Writing the handover record to the file the operator chose. */
class HandoverController(
    private val repository: HandoverRepository,
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    /** "spray-day-sprays-2026-09-14.csv" - dated, so two seasons do not collide. */
    fun suggestedFileName(nowEpochMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
        return "spray-day-sprays-$date.csv"
    }

    /** Writes the record; returns how many sprays are in it. */
    suspend fun exportTo(uri: Uri): Int {
        val rows = repository.rows()
        writeText(uri, repository.renderCsv(rows))
        return rows.size
    }

    private suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFileException("That file could not be written.")
        // A byte-order mark, so Excel opens accented names correctly rather than as
        // mojibake - the one thing that reliably goes wrong with CSV on Windows.
        stream.use { output ->
            output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
