package nz.mckenzie.sprayday.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AssetEntity::class,
        AssetPointEntity::class,
        ProductEntity::class,
        SprayEventEntity::class,
        SprayEventProductEntity::class,
        AssetProductDefaultEntity::class,
        RecordedSessionEntity::class,
        RecordedPointEntity::class,
        OfflineAreaEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class SprayDayDatabase : RoomDatabase() {

    abstract fun assetDao(): AssetDao
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
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
