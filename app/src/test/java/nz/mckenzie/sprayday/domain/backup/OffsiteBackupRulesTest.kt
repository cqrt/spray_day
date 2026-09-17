package nz.mckenzie.sprayday.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The off-site backup's decisions, which are the ones that have to be right.
 *
 * The first test here is the reason the feature has a rule at all. A phone that has been
 * wiped holds nothing, and a backup that ran before the operator restored would write that
 * nothing over the last copy of a season - the one mistake in this feature that cannot be
 * undone, because the thing that would undo it is the thing that was overwritten.
 */
class OffsiteBackupRulesTest {

    private val zone = ZoneId.of("Pacific/Auckland")

    private fun summary(
        assets: Int = 0,
        sprays: Int = 0,
        recordings: Int = 0,
        products: Int = 0
    ) = BackupSummary(assets = assets, sprays = sprays, recordings = recordings, products = products, points = 0)

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun `an empty database is never safe to write over a copy`() {
        assertFalse(OffsiteBackupRules.isSafeToWrite(summary()))
    }

    @Test
    fun `assets on their own are worth copying`() {
        assertTrue(OffsiteBackupRules.isSafeToWrite(summary(assets = 1)))
    }

    @Test
    fun `a product catalogue with no season on it is still worth copying`() {
        assertTrue(OffsiteBackupRules.isSafeToWrite(summary(products = 3)))
    }

    @Test
    fun `a recording with nothing else in it is still worth copying`() {
        assertTrue(OffsiteBackupRules.isSafeToWrite(summary(recordings = 1)))
    }

    @Test
    fun `the refusal says the copy was left alone`() {
        val message = OffsiteBackupRules.refusedBecauseEmpty()
        assertTrue(message, message.contains("Nothing was written"))
        assertTrue(message, message.contains("Restore from it instead"))
    }

    @Test
    fun `today and yesterday are words, not numbers`() {
        assertEquals("saved today", OffsiteBackupRules.ageOf(at("2026-09-17T20:00:00Z"), at("2026-09-17T21:00:00Z"), zone))
        assertEquals("saved yesterday", OffsiteBackupRules.ageOf(at("2026-09-16T20:00:00Z"), at("2026-09-17T21:00:00Z"), zone))
    }

    @Test
    fun `a copy from last week says how old it is and when it was saved`() {
        val label = OffsiteBackupRules.ageOf(at("2026-09-10T20:00:00Z"), at("2026-09-17T21:00:00Z"), zone)

        assertTrue(label, label.contains("7 days ago"))
        assertTrue(label, label.contains("11 Sep 2026"))
    }

    @Test
    fun `an old copy is dated rather than counted in days`() {
        val label = OffsiteBackupRules.ageOf(at("2026-01-05T20:00:00Z"), at("2026-09-17T21:00:00Z"), zone)

        assertFalse(label, label.contains("days ago"))
        assertTrue(label, label.contains("6 Jan 2026"))
    }

    @Test
    fun `a clock that has drifted into the future does not produce a negative age`() {
        val label = OffsiteBackupRules.ageOf(at("2026-09-20T20:00:00Z"), at("2026-09-17T21:00:00Z"), zone)

        assertTrue(label, label.startsWith("saved 21 Sep 2026"))
    }

    @Test
    fun `a device name becomes a file name`() {
        assertEquals("spray-day-pixel-8.json", OffsiteBackupRules.fileNameFor("Pixel 8"))
        assertEquals("spray-day-sdk-gphone64-x86-64.json", OffsiteBackupRules.fileNameFor("sdk_gphone64_x86_64"))
    }

    @Test
    fun `a device name with nothing usable in it still makes a file name`() {
        assertEquals("spray-day-device.json", OffsiteBackupRules.fileNameFor("  ???  "))
    }

    @Test
    fun `only this app's files are claimed as backups`() {
        assertTrue(OffsiteBackupRules.isBackupFileName("spray-day-pixel-8.json"))
        assertFalse(OffsiteBackupRules.isBackupFileName("README.md"))
        assertFalse(OffsiteBackupRules.isBackupFileName("spray-day-notes.txt"))
        assertFalse(OffsiteBackupRules.isBackupFileName("backups"))
    }

    @Test
    fun `the comparison names both sides with numbers`() {
        val line = OffsiteBackupRules.comparison(summary(assets = 4, sprays = 9), summary(assets = 1))

        assertTrue(line, line.contains("4 assets, 9 sprays"))
        assertTrue(line, line.contains("This phone: 1 asset,"))
    }
}
