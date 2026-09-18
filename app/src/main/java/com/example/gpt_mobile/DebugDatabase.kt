package com.example.gpt_mobile

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * AETHERION Debug Database
 * Room database for storing telemetry, diagnostics, and token metrics
 */
@Database(
    entities = [TelemetryEvent::class, HardwareDiagnostics::class, TokenMetrics::class],
    version = 1,
    exportSchema = true
)
abstract class DebugDatabase : RoomDatabase() {
    
    /**
     * DAO for database operations
     */
    abstract fun debugDao(): DebugDatabaseDao
    
    companion object {
        @Volatile
        private var INSTANCE: DebugDatabase? = null
        
        /**
         * Get the singleton instance of the database
         */
        fun getDatabase(context: Context): DebugDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DebugDatabase::class.java,
                    "aetherion_debug_database"
                )
                .fallbackToDestructiveMigration() // For development
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
