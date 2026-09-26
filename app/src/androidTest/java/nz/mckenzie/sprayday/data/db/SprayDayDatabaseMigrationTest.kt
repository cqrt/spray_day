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

    /**
     * The v2 -> v3 model change, against a populated database.
     *
     * This is the migration that matters: it renames tables and columns, and the tables
     * it drops are ones other rows point at with ON DELETE CASCADE. If the order is
     * wrong the sprays and the GPS fixes disappear, so the assertions below count them
     * rather than merely checking that the schema validates.
     */
    @Test
    fun migrationFrom2To3KeepsTheHistoryAndTurnsLabelsIntoGroups() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO tracks (id, name, areaLabel, notes, intervalDays, swathWidthM, " +
                    "active, createdAtEpochMs, lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (1, 'Estuary road', 'Estuary', NULL, 120, 3.0, 1, 1000, NULL, 2002.3)"
            )
            // Same block, typed differently: the group must not split in two over case.
            execSQL(
                "INSERT INTO tracks (id, name, areaLabel, notes, intervalDays, swathWidthM, " +
                    "active, createdAtEpochMs, lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (2, 'Estuary lagoon', 'estuary', NULL, 120, NULL, 1, 1000, NULL, 100.0)"
            )
            execSQL(
                "INSERT INTO tracks (id, name, areaLabel, notes, intervalDays, swathWidthM, " +
                    "active, createdAtEpochMs, lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (3, 'Lone block', NULL, NULL, 90, NULL, 1, 1000, NULL, 0.0)"
            )
            execSQL(
                "INSERT INTO track_points (trackId, sequence, lat, lng) " +
                    "VALUES (1, 0, -41.5, 173.95), (1, 1, -41.51, 173.96), " +
                    "(2, 0, -41.6, 173.9), (2, 1, -41.61, 173.91)"
            )
            execSQL(
                "INSERT INTO products (id, name, unit, rateText, notes, archived) " +
                    "VALUES (1, 'Glyphosate 360', 'mL', '10 mL/L', NULL, 0)"
            )
            execSQL(
                "INSERT INTO track_product_defaults (trackId, productId, defaultQuantityMl) " +
                    "VALUES (1, 1, 1500.0)"
            )
            execSQL(
                "INSERT INTO recorded_sessions (id, name, trackId, startedAtEpochMs, " +
                    "endedAtEpochMs, status, distanceM, durationMs, pointCount) " +
                    "VALUES (5, 'Estuary road', 1, 2000, 3000, 'FINISHED', 1234.5, 600000, 2)"
            )
            execSQL(
                "INSERT INTO recorded_points (sessionId, sequence, lat, lng, altitudeM, " +
                    "accuracyM, speedMps, bearingDeg, recordedAtEpochMs) " +
                    "VALUES (5, 0, -41.5, 173.95, 12.0, 4.5, 2.2, 180.0, 2000), " +
                    "(5, 1, -41.51, 173.96, 13.0, 4.0, 2.4, 181.0, 2010)"
            )
            execSQL(
                "INSERT INTO spray_events (id, trackId, sprayedAtEpochMs, waterLitres, " +
                    "operatorName, notes, distanceM, areaSqm, recordedSessionId) " +
                    "VALUES (7, 1, 2500, 400.0, 'Matt', NULL, 2350.0, 14100.0, 5)"
            )
            execSQL(
                "INSERT INTO spray_event_products (sprayEventId, productId, quantityMl) " +
                    "VALUES (7, 1, 1500.0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        // One group, holding both spellings of the same block.
        migrated.query("SELECT id, name FROM groups").use { cursor ->
            assertEquals("estuary and Estuary are one group", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Estuary", cursor.getString(1))
        }
        migrated.query("SELECT COUNT(*) FROM assets WHERE groupId IS NOT NULL").use { cursor ->
            cursor.moveToFirst()
            assertEquals("both estuary assets should be in the group", 2, cursor.getInt(0))
        }

        // Nothing is invented about how the work was done.
        migrated.query(
            "SELECT kind, shape, method, lengthM, groupId FROM assets WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("TRACK", cursor.getString(0))
            assertEquals("LINE", cursor.getString(1))
            assertEquals("UNSET", cursor.getString(2))
            assertEquals(2002.3, cursor.getDouble(3), 0.01)
            assertTrue("groupId should have been resolved", !cursor.isNull(4))
        }
        migrated.query("SELECT groupId FROM assets WHERE id = 3").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("an asset that was never labelled belongs to no group", cursor.isNull(0))
        }

        // The geometry, the pre-fill and the spray history all survived the rebuild. The
        // spray and its product line are the ones a wrong drop order would eat, because
        // they point at the table that had to be dropped and recreated.
        migrated.query("SELECT COUNT(*) FROM asset_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4, cursor.getInt(0))
        }
        migrated.query("SELECT assetId, defaultQuantityMl FROM asset_product_defaults").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1500.0, cursor.getDouble(1), 0.01)
        }
        migrated.query("SELECT id, assetId, waterLitres FROM spray_events").use { cursor ->
            assertTrue("the spray must still be there", cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(400.0, cursor.getDouble(2), 0.01)
        }
        migrated.query("SELECT COUNT(*) FROM spray_event_products").use { cursor ->
            cursor.moveToFirst()
            assertEquals("and so must the product it used", 1, cursor.getInt(0))
        }
        migrated.query("SELECT assetId FROM recorded_sessions WHERE id = 5").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        migrated.query("SELECT COUNT(*) FROM recorded_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals("every accepted fix must survive", 2, cursor.getInt(0))
        }

        migrated.close()
    }

    /**
     * The migration that adds pauses.
     *
     * There is nothing to carry over - no recording made before this had a pause in it - so what
     * it has to prove is that it leaves the recordings it runs over alone, and that the table it
     * adds behaves: a pause is written as an open one when the operator stops, and goes with the
     * recording it belongs to.
     */
    @Test
    fun migrationFrom3To4AddsBreaksWithoutTouchingTheRecordings() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO recorded_sessions (id, name, assetId, startedAtEpochMs, " +
                    "endedAtEpochMs, status, distanceM, durationMs, pointCount) " +
                    "VALUES (5, 'Estuary road', NULL, 2000, 3000, 'FINISHED', 1234.5, 600000, 2)"
            )
            execSQL(
                "INSERT INTO recorded_points (sessionId, sequence, lat, lng, altitudeM, " +
                    "accuracyM, speedMps, bearingDeg, recordedAtEpochMs) " +
                    "VALUES (5, 0, -41.5, 173.95, 12.0, 4.5, 2.2, 180.0, 2000), " +
                    "(5, 1, -41.51, 173.96, 13.0, 4.0, 2.4, 181.0, 2010)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        migrated.query("SELECT name, distanceM FROM recorded_sessions WHERE id = 5").use { cursor ->
            assertTrue("the recording must survive the migration", cursor.moveToFirst())
            assertEquals("Estuary road", cursor.getString(0))
            assertEquals(1234.5, cursor.getDouble(1), 0.01)
        }
        migrated.query("SELECT COUNT(*) FROM recorded_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals("and every fix of it", 2, cursor.getInt(0))
        }

        // The new table, with the two things about it that matter.
        migrated.execSQL(
            "INSERT INTO recorded_breaks (sessionId, fromEpochMs, toEpochMs) VALUES (5, 2100, 2900)"
        )
        migrated.execSQL(
            "INSERT INTO recorded_breaks (sessionId, fromEpochMs, toEpochMs) VALUES (5, 2500, NULL)"
        )
        migrated.query("SELECT fromEpochMs, toEpochMs FROM recorded_breaks ORDER BY fromEpochMs")
            .use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals(2100L, cursor.getLong(0))
                assertEquals(2900L, cursor.getLong(1))
                cursor.moveToNext()
                assertTrue("a pass still paused has no end to its break", cursor.isNull(1))
            }

        // A pause belongs to its pass and goes when it does. Foreign keys are enforced here the
        // way the app enforces them, because the harness opens the migrated database without
        // Room's own `PRAGMA foreign_keys = ON`: without this the delete would leave the pauses
        // behind and the test would be asserting about SQLite's default rather than the app's.
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL("DELETE FROM recorded_sessions WHERE id = 5")
        migrated.query("SELECT COUNT(*) FROM recorded_breaks").use { cursor ->
            cursor.moveToFirst()
            assertEquals("a pause goes when the recording it belongs to does", 0, cursor.getInt(0))
        }

        migrated.close()
    }

    /**
     * The migration that adds the two-pass fields.
     *
     * Every line in the database was sprayed in one pass - the app had no other idea of a job - so
     * the defaults are not a guess: they are what the record already means. What this has to prove
     * is that the assets and the recordings it runs over keep everything they had, that a line
     * that has never been told otherwise reads as one pass, and that the columns can hold the
     * other answer.
     */
    @Test
    fun migrationFrom4To5AddsTheTwoPassFieldsWithoutTouchingAnything() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO assets (id, name, kind, shape, method, groupId, notes, intervalDays, " +
                    "swathWidthM, active, createdAtEpochMs, lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (3, 'Estuary road', 'ROAD', 'LINE', 'KNAPSACK', NULL, 'both edges', " +
                    "120, 1.5, 1, 1000, 2000, 800.0)"
            )
            execSQL(
                "INSERT INTO recorded_sessions (id, name, assetId, startedAtEpochMs, " +
                    "endedAtEpochMs, status, distanceM, durationMs, pointCount) " +
                    "VALUES (5, 'Estuary road · 17 Sep', 3, 2000, 3000, 'FINISHED', 800.0, 600000, 2)"
            )
            execSQL(
                "INSERT INTO recorded_points (sessionId, sequence, lat, lng, altitudeM, " +
                    "accuracyM, speedMps, bearingDeg, recordedAtEpochMs) " +
                    "VALUES (5, 0, -41.5, 173.95, 12.0, 4.5, 2.2, 180.0, 2000), " +
                    "(5, 1, -41.51, 173.96, 13.0, 4.0, 2.4, 181.0, 2010)"
            )
            execSQL(
                "INSERT INTO recorded_breaks (sessionId, fromEpochMs, toEpochMs) VALUES (5, 2100, 2900)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        migrated.query(
            "SELECT name, swathWidthM, passesRequired, passSeparationM, notes " +
                "FROM assets WHERE id = 3"
        ).use { cursor ->
            assertTrue("the asset must survive the migration", cursor.moveToFirst())
            assertEquals("Estuary road", cursor.getString(0))
            assertEquals(1.5, cursor.getDouble(1), 0.01)
            assertEquals("a line nobody has said otherwise about is one pass", 1, cursor.getInt(2))
            assertTrue("and has no separation to tell sides apart by", cursor.isNull(3))
            assertEquals("both edges", cursor.getString(4))
        }
        migrated.query("SELECT pointCount, bothSidesClaimed FROM recorded_sessions WHERE id = 5")
            .use { cursor ->
                assertTrue("the recording must survive too", cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                assertEquals(
                    "an old recording holds no claim nobody made",
                    0,
                    cursor.getInt(1)
                )
            }
        migrated.query("SELECT COUNT(*) FROM recorded_points").use { cursor ->
            cursor.moveToFirst()
            assertEquals("and every fix of it", 2, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM recorded_breaks").use { cursor ->
            cursor.moveToFirst()
            assertEquals("and its pause", 1, cursor.getInt(0))
        }

        // And the columns hold the other answer, which is the one the feature is for.
        migrated.execSQL(
            "UPDATE assets SET passesRequired = 2, passSeparationM = 3.0 WHERE id = 3"
        )
        migrated.execSQL("UPDATE recorded_sessions SET bothSidesClaimed = 1 WHERE id = 5")
        migrated.query(
            "SELECT passesRequired, passSeparationM FROM assets WHERE id = 3"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
            assertEquals(3.0, cursor.getDouble(1), 0.01)
        }
        migrated.query("SELECT bothSidesClaimed FROM recorded_sessions WHERE id = 5").use { cursor ->
            cursor.moveToFirst()
            assertEquals("and the operator's word can be written", 1, cursor.getInt(0))
        }

        migrated.close()
    }

    /**
     * The migration that gives every vertex a path to belong to.
     *
     * This is the one that has to be exactly right, because it runs over every track in the database
     * and there is no way back: what it must prove is that a v5 track comes out as **exactly the line
     * it was** - all its vertices, in order, on path 0 - so that an install nobody touches behaves as
     * it did. The new column's default is the whole of the carrying over; this is what checks the
     * default is doing what it says.
     *
     * The index moves with it, and that is checked by using it: two paths may each have a vertex in
     * position 1, which `(assetId, sequence)` alone would have refused.
     */
    @Test
    fun migrationFrom5To6PutsEveryExistingVertexOnTheLine() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO assets (id, name, kind, shape, method, groupId, notes, intervalDays, " +
                    "swathWidthM, passesRequired, passSeparationM, active, createdAtEpochMs, " +
                    "lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (3, 'Estuary road', 'TRACK', 'LINE', 'BOOM', NULL, NULL, 120, NULL, 1, " +
                    "NULL, 1, 1000, NULL, 222.4)"
            )
            execSQL(
                "INSERT INTO asset_points (assetId, sequence, lat, lng) VALUES " +
                    "(3, 0, -41.50, 173.95), (3, 1, -41.51, 173.96), (3, 2, -41.52, 173.97)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        migrated.query(
            "SELECT sequence, lat, lng, pathIndex FROM asset_points WHERE assetId = 3 ORDER BY sequence"
        ).use { cursor ->
            assertEquals("every vertex of the line survives", 3, cursor.count)
            var index = 0
            while (cursor.moveToNext()) {
                assertEquals("in the order it was drawn", index, cursor.getInt(0))
                assertEquals("on the line, which is path 0", 0, cursor.getInt(3))
                index++
            }
            assertTrue("and the asset it belongs to still has it", index == 3)
        }

        // Two paths, each with a vertex in position 1: what the new index is for, and what the old one
        // - unique on (assetId, sequence) - would have refused.
        migrated.execSQL(
            "INSERT INTO asset_points (assetId, pathIndex, sequence, lat, lng) VALUES " +
                "(3, 1, 0, -41.51, 173.96), (3, 1, 1, -41.55, 173.90)"
        )
        migrated.query(
            "SELECT COUNT(*) FROM asset_points WHERE assetId = 3 AND pathIndex = 1"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("a side track of the same asset is stored", 2, cursor.getInt(0))
        }
        migrated.query("SELECT lengthM FROM assets WHERE id = 3").use { cursor ->
            cursor.moveToFirst()
            assertEquals(
                "and the length the v5 track was carrying is untouched",
                222.4,
                cursor.getDouble(0),
                0.01
            )
        }

        migrated.close()
    }

    /**
     * The migration that gives a carpark its ground.
     *
     * The column is defaulted rather than computed, because every asset in a v6 database is a line or a
     * place and neither encloses anything: what has to be proved is that a v6 row comes out of it
     * **exactly as it was**, with no ground invented for it - and that a carpark written afterwards can
     * carry its own. `runMigrationsAndValidate` also checks the schema it produces against the one Room
     * exports for v7, which is what stops the column and the entity drifting apart.
     */
    @Test
    fun migrationFrom6To7AddsTheGroundWithoutClaimingAny() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO assets (id, name, kind, shape, method, groupId, notes, intervalDays, " +
                    "swathWidthM, passesRequired, passSeparationM, active, createdAtEpochMs, " +
                    "lastSprayedAtEpochMs, lengthM) " +
                    "VALUES (3, 'Estuary road', 'TRACK', 'LINE', 'BOOM', NULL, NULL, 120, 3.0, 1, " +
                    "NULL, 1, 1000, NULL, 222.4)"
            )
            execSQL(
                "INSERT INTO asset_points (assetId, pathIndex, sequence, lat, lng) VALUES " +
                    "(3, 0, 0, -41.50, 173.95), (3, 0, 1, -41.51, 173.96)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        migrated.query("SELECT areaM2, lengthM FROM assets WHERE id = 3").use { cursor ->
            assertTrue("the road is still there", cursor.moveToFirst())
            assertEquals(
                "a line encloses nothing, and nothing is claimed for it",
                0.0,
                cursor.getDouble(0),
                1e-9
            )
            assertEquals("and its length is untouched", 222.4, cursor.getDouble(1), 0.01)
        }
        migrated.query("SELECT COUNT(*) FROM asset_points WHERE assetId = 3").use { cursor ->
            cursor.moveToFirst()
            assertEquals("with every vertex of its line", 2, cursor.getInt(0))
        }

        // A carpark written after the migration carries the ground measured off its corners: the column
        // is writable, which is the other half of what a migration has to leave behind.
        migrated.execSQL(
            "INSERT INTO assets (id, name, kind, shape, method, groupId, notes, intervalDays, " +
                "swathWidthM, passesRequired, passSeparationM, active, createdAtEpochMs, " +
                "lastSprayedAtEpochMs, lengthM, areaM2) " +
                "VALUES (4, 'Works carpark', 'CARPARK', 'AREA', 'UNSET', NULL, NULL, 120, NULL, 1, " +
                "NULL, 1, 1000, NULL, 260.0, 3500.0)"
        )
        migrated.query("SELECT areaM2 FROM assets WHERE id = 4").use { cursor ->
            cursor.moveToFirst()
            assertEquals("a carpark's measured ground is stored", 3_500.0, cursor.getDouble(0), 0.01)
        }

        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
