package dev.chungjungsoo.gptmobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TelemetryEvent::class,
        HardwareDiagnostics::class,
        TokenMetrics::class
    ],
    version = 1,
    exportSchema = true
)
abstract class DebugDatabase : RoomDatabase() {

    abstract fun telemetryDao(): DebugDatabaseDao
    abstract fun hardwareDiagnosticsDao(): DebugDatabaseDao
    abstract fun tokenMetricsDao(): DebugDatabaseDao

    companion object {
        @Volatile
        private var INSTANCE: DebugDatabase? = null

        fun getDatabase(context: Context): DebugDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DebugDatabase::class.java,
                    "debug_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
