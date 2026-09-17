package nz.mckenzie.sprayday.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a backup carries besides the season: the switches, and what it deliberately does not
 * carry.
 *
 * The last test here is the one worth having. The LINZ key and the GitHub token are
 * credentials, and the backup is a file that may be read by whoever finds it - the token
 * especially, since the file it would be written into lives in the very repository the token
 * can write to.
 */
class BackupSettingsRecordTest {

    private fun document(settings: BackupSettingsRecord?) = BackupDocument(
        exportedAtEpochMs = 1_760_000_000_000L,
        appVersion = "0.6.3",
        settings = settings
    )

    @Test
    fun `the switches come back out of a file as they went in`() {
        val record = BackupSettingsRecord(
            remindersEnabled = false,
            updateChecksEnabled = true,
            backUpAutomatically = true,
            destination = "GITHUB"
        )

        val read = BackupFormat.decode(BackupFormat.encode(document(record))).settings

        assertEquals(record, read)
    }

    @Test
    fun `a file written before switches existed still restores, with none in it`() {
        val text = """
            {
              "format": "spray-day-backup",
              "version": 2,
              "exportedAtEpochMs": 1760000000000,
              "appVersion": "0.6.0",
              "assets": [ { "id": 1, "name": "Estuary road", "intervalDays": 21, "createdAtEpochMs": 1 } ]
            }
        """.trimIndent()

        val restored = BackupFormat.decode(text)

        assertNull(restored.settings)
        assertEquals(1, restored.assets.size)
    }

    @Test
    fun `adding the switches did not change the format version`() {
        // A v1 file has to keep restoring into this build, so the version is about the shape
        // of the data, not about how many fields there are.
        assertEquals(2, BackupDocument.VERSION)
        assertEquals(2, document(BackupSettingsRecord()).version)
    }

    @Test
    fun `the file carries no credentials`() {
        val text = BackupFormat.encode(
            document(BackupSettingsRecord(destination = "GITHUB"))
        )

        assertFalse(text, text.contains("token", ignoreCase = true))
        assertFalse(text, text.contains("linz", ignoreCase = true))
        assertFalse(text, text.contains("Bearer", ignoreCase = true))
    }

    @Test
    fun `a destination name this build does not know is not a destination`() {
        assertEquals(BackupDestination.GITHUB, BackupDestination.fromName("GITHUB"))
        assertEquals(BackupDestination.OFF, BackupDestination.fromName("DROPBOX"))
        assertEquals(BackupDestination.OFF, BackupDestination.fromName(null))
    }

    @Test
    fun `a copy's size is said in units a person reads`() {
        assertTrue(StoredBackup("a", "a.json", 3_400L).sizeLabel.contains("kB"))
        assertEquals("3.4 MB", StoredBackup("a", "a.json", 3_400_000L).sizeLabel)
        assertEquals("unknown size", StoredBackup("a", "a.json", -1L).sizeLabel)
    }
}
