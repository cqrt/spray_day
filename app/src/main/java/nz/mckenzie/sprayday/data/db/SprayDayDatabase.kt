package nz.mckenzie.sprayday.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        TrackPointEntity::class,
        ProductEntity::class,
        SprayEventEntity::class,
        SprayEventProductEntity::class,
        TrackProductDefaultEntity::class,
        RecordedSessionEntity::class,
        RecordedPointEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SprayDayDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun productDao(): ProductDao
    abstract fun sprayEventDao(): SprayEventDao
    abstract fun recordingDao(): RecordingDao

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
                ).build().also { instance = it }
            }
    }
}
