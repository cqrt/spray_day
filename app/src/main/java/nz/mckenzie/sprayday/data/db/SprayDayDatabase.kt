package nz.mckenzie.sprayday.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        GroupEntity::class,
        AssetEntity::class,
        AssetPointEntity::class,
        ProductEntity::class,
        SprayEventEntity::class,
        SprayEventProductEntity::class,
        AssetProductDefaultEntity::class,
        RecordedSessionEntity::class,
        RecordedPointEntity::class,
        RecordedBreakEntity::class,
        OfflineAreaEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class SprayDayDatabase : RoomDatabase() {

    abstract fun assetDao(): AssetDao
    abstract fun groupDao(): GroupDao
    abstract fun productDao(): ProductDao
    abstract fun sprayEventDao(): SprayEventDao
    abstract fun recordingDao(): RecordingDao
    abstract fun offlineAreaDao(): OfflineAreaDao

    /** Reads and writes everything, for backup and restore. */
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "spray_day.db"

        @Volatile
        private var instance: SprayDayDatabase? = null

        /** Process-wide singleton; use an in-memory builder in tests instead. */
        fun get(context: Context): SprayDayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SprayDayDatabase::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
    }
}
