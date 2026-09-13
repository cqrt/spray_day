package nz.mckenzie.sprayday.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
