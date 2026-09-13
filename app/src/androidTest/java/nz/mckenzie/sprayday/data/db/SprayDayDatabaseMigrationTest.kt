package nz.mckenzie.sprayday.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the v1 -> v2 migration both preserves existing data and produces a
 * schema Room accepts. A migration that merely "runs" is not enough: if the
 * resulting schema differs from the generated one, Room refuses to open the
 * database on the next launch.
 */
@RunWith(AndroidJUnit4::class)
class SprayDayDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SprayDayDatabase::class.java
    )

    @Test
    fun migrationFrom1To2KeepsTracksAndAddsOfflineAreas() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO tracks (name, areaLabel, notes, intervalDays, swathWidthM, " +
                    "active, createdAtEpochMs, lastSprayedAtEpochMs, lengthM) " +
                    "VALUES ('Old stopbank', 'Wairau', NULL, 120, 3.0, 1, 1000, NULL, 2002.3)"
            )
            execSQL(
                "INSERT INTO products (name, unit, rateText, notes, archived) " +
                    "VALUES ('Glyphosate 360', 'mL', '10 mL/L', NULL, 0)"
            )
            close()
        }

        // validateDroppedTables = true, so a stray table fails the test too.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        migrated.query("SELECT name, lengthM FROM tracks").use { cursor ->
            assertTrue("the existing track should survive the migration", cursor.moveToFirst())
            assertEquals("Old stopbank", cursor.getString(0))
            assertEquals(2002.3, cursor.getDouble(1), 0.01)
        }

        migrated.query("SELECT COUNT(*) FROM products").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // The new table is present and usable, including its index.
        migrated.execSQL(
            "INSERT INTO offline_areas (name, minLat, minLng, maxLat, maxLng, minZoom, " +
                "maxZoom, plannedTiles, downloadedTiles, bytes, createdAtEpochMs, " +
                "completedAtEpochMs, lastError) " +
                "VALUES ('Wairau block', -41.6, 173.9, -41.4, 174.1, 12, 16, 304, 0, 0, 2000, NULL, NULL)"
        )
        migrated.query("SELECT name, plannedTiles FROM offline_areas").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Wairau block", cursor.getString(0))
            assertEquals(304L, cursor.getLong(1))
        }

        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
