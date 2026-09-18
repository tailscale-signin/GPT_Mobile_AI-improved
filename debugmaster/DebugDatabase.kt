package com.gptmobileai.debug

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * AETHERION Debug Mode - Room Database Singleton
 * 
 * Central database instance for real-time telemetry, hardware diagnostics,
 * and token metrics. Uses Room for type-safe, coroutine-friendly data access.
 * 
 * @author AETHERION Debug Team
 * @version 1.0.0
 */

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
    abstract fun diagnosticsDao(): DebugDatabaseDao
    abstract fun tokenMetricsDao(): DebugDatabaseDao
    
    companion object {
        @Volatile
        private var INSTANCE: DebugDatabase? = null
        
        fun getDatabase(context: Context): DebugDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DebugDatabase::class.java,
                    "aetherion_debug_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Database created successfully
                        }
                    })
                    .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}