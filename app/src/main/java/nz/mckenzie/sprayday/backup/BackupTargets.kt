package nz.mckenzie.sprayday.backup

import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.backup.BackupDestination
import nz.mckenzie.sprayday.domain.backup.BackupTarget
import nz.mckenzie.sprayday.domain.backup.OffsiteBackupRules

/**
 * The destination the settings describe.
 *
 * Null is a real answer and the common one: the operator may have turned the off-site copy
 * on but not yet given it a token, or not yet chosen the file. The screen says which of the
 * two is missing, rather than the app quietly doing nothing.
 *
 * The file name is this device's, from its model, so two phones pointed at the same
 * repository never write over each other's copy.
 */
object BackupTargets {

    suspend fun from(context: Context, settings: SettingsRepository): BackupTarget? =
        when (settings.backupDestination.first()) {
            BackupDestination.OFF -> null

            BackupDestination.FILE ->
                settings.backupFileUri.first()
                    .takeIf { it.isNotBlank() }
                    ?.let { uri -> FileBackupTarget(context, uri.toUri()) }

            BackupDestination.GITHUB -> {
                val token = settings.backupToken.first()
                if (token.isBlank()) {
                    null
                } else {
                    GitHubBackupTarget(
                        repo = settings.backupRepo.first(),
                        token = token,
                        fileName = OffsiteBackupRules.fileNameFor(Build.MODEL)
                    )
                }
            }
        }

    /** This device's file name, as the screen shows it before anything is written. */
    fun fileNameForThisDevice(): String = OffsiteBackupRules.fileNameFor(Build.MODEL)
}
