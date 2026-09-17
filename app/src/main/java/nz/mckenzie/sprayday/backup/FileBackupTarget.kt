package nz.mckenzie.sprayday.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.backup.BackupFileException
import nz.mckenzie.sprayday.domain.backup.BackupTarget
import nz.mckenzie.sprayday.domain.backup.StoredBackup

/**
 * Keeps the backup in the file the operator chose, through the system file picker.
 *
 * This is the destination that can be anywhere - a Downloads folder, a USB stick, a
 * synced folder, an email attachment - because the app never learns what is behind the
 * `Uri`. It is also the destination that holds only one copy, overwritten in place, which
 * is exactly why it is worth having the GitHub one as well.
 */
class FileBackupTarget(
    private val context: Context,
    private val uri: Uri
) : BackupTarget {

    override val label: String get() = displayName() ?: "the chosen file"

    override suspend fun save(text: String): StoredBackup = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFileException("That file could not be written.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        current() ?: throw BackupFileException("That file was written but cannot be read back.")
    }

    /**
     * The one file, or nothing.
     *
     * Nothing is not a failure: the operator may have chosen the destination before ever
     * having written it, and a screen that reports an error for that would be wrong.
     */
    override suspend fun list(): List<StoredBackup> = listOfNotNull(current())

    override suspend fun read(reference: String): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw BackupFileException("That file could not be opened.")
    }

    /** The file as it stands, or null when there is not one yet. */
    private fun current(): StoredBackup? {
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        val name = displayName() ?: return null
        return StoredBackup(reference = uri.toString(), name = name, sizeBytes = size)
    }

    private fun displayName(): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
}
