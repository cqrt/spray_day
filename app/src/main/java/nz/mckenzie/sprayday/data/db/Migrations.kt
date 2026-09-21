package nz.mckenzie.sprayday.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/**
 * Adds the offline imagery areas table.
 *
 * The SQL here must match exactly what Room generates, or Room refuses to open
 * the database - so it is copied from the exported schema and covered by
 * [nz.mckenzie.sprayday.data.db.SprayDayDatabaseMigrationTest].
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Byte-for-byte the SQL Room exports for this entity (from
        // app/schemas/.../2.json), so the migrated database validates against
        // the generated schema.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offline_areas` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`minLat` REAL NOT NULL, " +
                "`minLng` REAL NOT NULL, " +
                "`maxLat` REAL NOT NULL, " +
                "`maxLng` REAL NOT NULL, " +
                "`minZoom` INTEGER NOT NULL, " +
                "`maxZoom` INTEGER NOT NULL, " +
                "`plannedTiles` INTEGER NOT NULL, " +
                "`downloadedTiles` INTEGER NOT NULL, " +
                "`bytes` INTEGER NOT NULL, " +
                "`createdAtEpochMs` INTEGER NOT NULL, " +
                "`completedAtEpochMs` INTEGER, " +
                "`lastError` TEXT)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_areas_createdAtEpochMs` " +
                "ON `offline_areas` (`createdAtEpochMs`)"
        )
    }
}

/**
 * Turns planned tracks into assets: renames the tables, gives every asset a kind, a
 * shape and a spray method, and lifts the old free-text "block or area" label into a
 * real group row.
 *
 * Two things make this more than a set of renames.
 *
 * SQLite only learnt `ALTER TABLE ... RENAME COLUMN` in 3.25, which is Android 11 and
 * later, so every touched table is rebuilt rather than relabelled.
 *
 * And foreign keys are enforced, so a table cannot simply be dropped while children
 * point at it - the cascades would delete the very history this migration exists to
 * protect. The order below is therefore deliberate: build the new tables and copy into
 * them, stage the tables whose rows other tables point at, then drop the old tables
 * children first, recreate the real ones, copy back, and clean up.
 *
 * The SQL is written out in the shape Room exports (see app/schemas/.../3.json), and
 * [nz.mckenzie.sprayday.data.db.SprayDayDatabaseMigrationTest] proves it against a
 * populated v2 database.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- Groups, out of the old free-text labels --------------------------------
        // A label that named three tracks becomes one group holding all three. The
        // name column is COLLATE NOCASE, so the GROUP BY collapses labels differing
        // only in case and the unique index then refuses the near-duplicate outright.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `groups` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL COLLATE NOCASE, " +
                "`notes` TEXT)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_groups_name` ON `groups` (`name`)")
        db.execSQL(
            "INSERT OR IGNORE INTO `groups` (`name`) " +
                "SELECT TRIM(`areaLabel`) FROM `tracks` " +
                "WHERE `areaLabel` IS NOT NULL AND TRIM(`areaLabel`) <> '' " +
                "GROUP BY TRIM(`areaLabel`) COLLATE NOCASE"
        )

        // --- Assets, from tracks ----------------------------------------------------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `assets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`shape` TEXT NOT NULL, " +
                "`method` TEXT NOT NULL, " +
                "`groupId` INTEGER, " +
                "`notes` TEXT, " +
                "`intervalDays` INTEGER NOT NULL, " +
                "`swathWidthM` REAL, " +
                "`active` INTEGER NOT NULL, " +
                "`createdAtEpochMs` INTEGER NOT NULL, " +
                "`lastSprayedAtEpochMs` INTEGER, " +
                "`lengthM` REAL NOT NULL, " +
                "FOREIGN KEY(`groupId`) REFERENCES `groups`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_active` ON `assets` (`active`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_assets_lastSprayedAtEpochMs` " +
                "ON `assets` (`lastSprayedAtEpochMs`)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_groupId` ON `assets` (`groupId`)")

        // Everything that existed is a track drawn as a line, with its method unrecorded:
        // inventing BOOM here would put a claim in the record that nobody made.
        db.execSQL(
            "INSERT INTO `assets` (`id`, `name`, `kind`, `shape`, `method`, `groupId`, `notes`, " +
                "`intervalDays`, `swathWidthM`, `active`, `createdAtEpochMs`, " +
                "`lastSprayedAtEpochMs`, `lengthM`) " +
                "SELECT t.`id`, t.`name`, '${AssetKind.TRACK.name}', '${AssetShape.LINE.name}', " +
                "'${SprayMethod.UNSET.name}', " +
                "(SELECT g.`id` FROM `groups` g WHERE g.`name` = TRIM(t.`areaLabel`)), " +
                "t.`notes`, t.`intervalDays`, t.`swathWidthM`, t.`active`, " +
                "t.`createdAtEpochMs`, t.`lastSprayedAtEpochMs`, t.`lengthM` " +
                "FROM `tracks` t"
        )

        // --- Geometry and product pre-fills -----------------------------------------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `asset_points` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`assetId` INTEGER NOT NULL, " +
                "`sequence` INTEGER NOT NULL, " +
                "`lat` REAL NOT NULL, " +
                "`lng` REAL NOT NULL, " +
                "FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_points_assetId` ON `asset_points` (`assetId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_points_assetId_sequence` " +
                "ON `asset_points` (`assetId`, `sequence`)"
        )
        db.execSQL(
            "INSERT INTO `asset_points` (`id`, `assetId`, `sequence`, `lat`, `lng`) " +
                "SELECT `id`, `trackId`, `sequence`, `lat`, `lng` FROM `track_points`"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `asset_product_defaults` (" +
                "`assetId` INTEGER NOT NULL, " +
                "`productId` INTEGER NOT NULL, " +
                "`defaultQuantityMl` REAL, " +
                "PRIMARY KEY(`assetId`, `productId`), " +
                "FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_product_defaults_assetId` " +
                "ON `asset_product_defaults` (`assetId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_asset_product_defaults_productId` " +
                "ON `asset_product_defaults` (`productId`)"
        )
        db.execSQL(
            "INSERT INTO `asset_product_defaults` (`assetId`, `productId`, `defaultQuantityMl`) " +
                "SELECT `trackId`, `productId`, `defaultQuantityMl` FROM `track_product_defaults`"
        )

        // --- Stage the tables that other rows point at ------------------------------
        // spray_events, its product lines and the recording tables are rebuilt too,
        // because their trackId column is renamed. Dropping a parent while children
        // still reference it fires ON DELETE CASCADE - which would quietly delete every
        // spray and every GPS fix - so the rows wait in staging tables that declare no
        // foreign keys at all while the real tables are recreated underneath them.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmp_spray_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assetId` INTEGER NOT NULL, " +
                "`sprayedAtEpochMs` INTEGER NOT NULL, `waterLitres` REAL, `operatorName` TEXT, " +
                "`notes` TEXT, `distanceM` REAL, `areaSqm` REAL, `recordedSessionId` INTEGER)"
        )
        db.execSQL(
            "INSERT INTO `tmp_spray_events` (`id`, `assetId`, `sprayedAtEpochMs`, `waterLitres`, " +
                "`operatorName`, `notes`, `distanceM`, `areaSqm`, `recordedSessionId`) " +
                "SELECT `id`, `trackId`, `sprayedAtEpochMs`, `waterLitres`, `operatorName`, " +
                "`notes`, `distanceM`, `areaSqm`, `recordedSessionId` FROM `spray_events`"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmp_spray_event_products` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sprayEventId` INTEGER NOT NULL, " +
                "`productId` INTEGER NOT NULL, `quantityMl` REAL NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `tmp_spray_event_products` (`id`, `sprayEventId`, `productId`, `quantityMl`) " +
                "SELECT `id`, `sprayEventId`, `productId`, `quantityMl` FROM `spray_event_products`"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmp_recorded_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`assetId` INTEGER, `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER, " +
                "`status` TEXT NOT NULL, `distanceM` REAL NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`pointCount` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `tmp_recorded_sessions` (`id`, `name`, `assetId`, `startedAtEpochMs`, " +
                "`endedAtEpochMs`, `status`, `distanceM`, `durationMs`, `pointCount`) " +
                "SELECT `id`, `name`, `trackId`, `startedAtEpochMs`, `endedAtEpochMs`, " +
                "`status`, `distanceM`, `durationMs`, `pointCount` FROM `recorded_sessions`"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tmp_recorded_points` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                "`altitudeM` REAL, `accuracyM` REAL, `speedMps` REAL, `bearingDeg` REAL, " +
                "`recordedAtEpochMs` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `tmp_recorded_points` (`id`, `sessionId`, `sequence`, `lat`, `lng`, " +
                "`altitudeM`, `accuracyM`, `speedMps`, `bearingDeg`, `recordedAtEpochMs`) " +
                "SELECT `id`, `sessionId`, `sequence`, `lat`, `lng`, `altitudeM`, `accuracyM`, " +
                "`speedMps`, `bearingDeg`, `recordedAtEpochMs` FROM `recorded_points`"
        )

        // --- Drop the old tables, children before parents ---------------------------
        db.execSQL("DROP TABLE IF EXISTS `recorded_points`")
        db.execSQL("DROP TABLE IF EXISTS `recorded_sessions`")
        db.execSQL("DROP TABLE IF EXISTS `spray_event_products`")
        db.execSQL("DROP TABLE IF EXISTS `spray_events`")
        db.execSQL("DROP TABLE IF EXISTS `track_product_defaults`")
        db.execSQL("DROP TABLE IF EXISTS `track_points`")
        db.execSQL("DROP TABLE IF EXISTS `tracks`")

        // --- Recreate them under their new names, and copy the rows back ------------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spray_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `assetId` INTEGER NOT NULL, " +
                "`sprayedAtEpochMs` INTEGER NOT NULL, `waterLitres` REAL, `operatorName` TEXT, " +
                "`notes` TEXT, `distanceM` REAL, `areaSqm` REAL, `recordedSessionId` INTEGER, " +
                "FOREIGN KEY(`assetId`) REFERENCES `assets`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_spray_events_assetId` ON `spray_events` (`assetId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_spray_events_sprayedAtEpochMs` " +
                "ON `spray_events` (`sprayedAtEpochMs`)"
        )
        db.execSQL(
            "INSERT INTO `spray_events` (`id`, `assetId`, `sprayedAtEpochMs`, `waterLitres`, " +
                "`operatorName`, `notes`, `distanceM`, `areaSqm`, `recordedSessionId`) " +
                "SELECT `id`, `assetId`, `sprayedAtEpochMs`, `waterLitres`, `operatorName`, " +
                "`notes`, `distanceM`, `areaSqm`, `recordedSessionId` FROM `tmp_spray_events`"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `spray_event_products` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sprayEventId` INTEGER NOT NULL, " +
                "`productId` INTEGER NOT NULL, `quantityMl` REAL NOT NULL, " +
                "FOREIGN KEY(`sprayEventId`) REFERENCES `spray_events`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_spray_event_products_sprayEventId` " +
                "ON `spray_event_products` (`sprayEventId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_spray_event_products_productId` " +
                "ON `spray_event_products` (`productId`)"
        )
        db.execSQL(
            "INSERT INTO `spray_event_products` (`id`, `sprayEventId`, `productId`, `quantityMl`) " +
                "SELECT `id`, `sprayEventId`, `productId`, `quantityMl` " +
                "FROM `tmp_spray_event_products`"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recorded_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`assetId` INTEGER, `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER, " +
                "`status` TEXT NOT NULL, `distanceM` REAL NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`pointCount` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recorded_sessions_assetId` " +
                "ON `recorded_sessions` (`assetId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recorded_sessions_startedAtEpochMs` " +
                "ON `recorded_sessions` (`startedAtEpochMs`)"
        )
        db.execSQL(
            "INSERT INTO `recorded_sessions` (`id`, `name`, `assetId`, `startedAtEpochMs`, " +
                "`endedAtEpochMs`, `status`, `distanceM`, `durationMs`, `pointCount`) " +
                "SELECT `id`, `name`, `assetId`, `startedAtEpochMs`, `endedAtEpochMs`, " +
                "`status`, `distanceM`, `durationMs`, `pointCount` FROM `tmp_recorded_sessions`"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recorded_points` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                "`altitudeM` REAL, `accuracyM` REAL, `speedMps` REAL, `bearingDeg` REAL, " +
                "`recordedAtEpochMs` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `recorded_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recorded_points_sessionId` " +
                "ON `recorded_points` (`sessionId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recorded_points_sessionId_sequence` " +
                "ON `recorded_points` (`sessionId`, `sequence`)"
        )
        db.execSQL(
            "INSERT INTO `recorded_points` (`id`, `sessionId`, `sequence`, `lat`, `lng`, " +
                "`altitudeM`, `accuracyM`, `speedMps`, `bearingDeg`, `recordedAtEpochMs`) " +
                "SELECT `id`, `sessionId`, `sequence`, `lat`, `lng`, `altitudeM`, `accuracyM`, " +
                "`speedMps`, `bearingDeg`, `recordedAtEpochMs` FROM `tmp_recorded_points`"
        )

        // --- And the staging tables have done their job -----------------------------
        db.execSQL("DROP TABLE IF EXISTS `tmp_spray_event_products`")
        db.execSQL("DROP TABLE IF EXISTS `tmp_spray_events`")
        db.execSQL("DROP TABLE IF EXISTS `tmp_recorded_points`")
        db.execSQL("DROP TABLE IF EXISTS `tmp_recorded_sessions`")
    }
}

/**
 * Adds the pauses of a recording.
 *
 * A pause is written down rather than worked out later from the fixes, because the fixes
 * cannot tell a pause from a dropped signal - and the two mean opposite things: the ground
 * under a dropped signal was driven and sprayed, the ground across a pause was not. Nothing
 * to carry over: every recording made before this simply has no pauses in it, and a gap of
 * missing fixes in one of those is read as ground the pass drove, which is what it was.
 *
 * The SQL is written out in the shape Room exports (see app/schemas/.../4.json), and
 * [nz.mckenzie.sprayday.data.db.SprayDayDatabaseMigrationTest] proves it against a populated
 * v3 database.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recorded_breaks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`fromEpochMs` INTEGER NOT NULL, `toEpochMs` INTEGER, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `recorded_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_recorded_breaks_sessionId` " +
                "ON `recorded_breaks` (`sessionId`)"
        )
    }
}

/**
 * Adds the two-pass fields: how many passes a line's job takes, how far apart they run, and
 * the operator's word that both sides were done on a pass the fixes could not vouch for.
 *
 * Every asset in the database was sprayed in one pass - the app had no other idea of a job -
 * so the default of one is not a guess, it is what the record already means. The two columns
 * carry a SQL default because SQLite will not add a NOT NULL column without one; nothing else
 * needs carrying over, and no existing row is touched.
 *
 * [nz.mckenzie.sprayday.data.db.SprayDayDatabaseMigrationTest] proves it against a populated
 * v4 database.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `assets` ADD COLUMN `passesRequired` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `assets` ADD COLUMN `passSeparationM` REAL")
        db.execSQL(
            "ALTER TABLE `recorded_sessions` ADD COLUMN `bothSidesClaimed` INTEGER NOT NULL " +
                "DEFAULT 0"
        )
    }
}

/**
 * Gives every vertex a path to belong to, so a track can be a line with side tracks.
 *
 * The column is added with a SQL default of 0 and that default is the whole of the carrying over:
 * every vertex in the database already belongs to the one line its asset is, so every existing track
 * comes through this migration as exactly the line it was. That is the promise a migration has to
 * keep - an install nobody touches behaves as it did - and here it is kept by the default rather than
 * by a single row being copied.
 *
 * The unique index moves with it: `(assetId, sequence)` says a path has one vertex per position, and
 * `(assetId, pathIndex, sequence)` says the same thing per path. Two side tracks leaving the same
 * junction vertex are two paths starting at the same place, which the old index - had the paths
 * shared one sequence - could not have expressed.
 *
 * [nz.mckenzie.sprayday.data.db.SprayDayDatabaseMigrationTest] proves it against a populated v5
 * database, and the schema it produces is the one Room exports for v6.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `asset_points` ADD COLUMN `pathIndex` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("DROP INDEX IF EXISTS `index_asset_points_assetId_sequence`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_asset_points_assetId_pathIndex_sequence` " +
                "ON `asset_points` (`assetId`, `pathIndex`, `sequence`)"
        )
    }
}
