package dev.chungjungsoo.gptmobile.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.ChatDatabase
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.LocalModelDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val V1_DATABASE_NAME = "chat"
    private const val V2_DATABASE_NAME = "chat_database"

    private fun getDeviceRamGb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4L
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024L * 1024L * 1024L)
    }

    private fun createPragmaCallback(context: Context): RoomDatabase.Callback {
        return object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val totalRamGb = getDeviceRamGb(context)
                if (totalRamGb >= 10L) {
                    // High-RAM (>=10-12GB) flagship device optimizations:
                    // 1. Allocate 64MB RAM directly to SQLite B-Tree page cache (-65536 is kibibytes)
                    db.execSQL("PRAGMA cache_size = -65536;")
                    // 2. Keep temp tables, index sorts, and intermediate queries purely in RAM
                    db.execSQL("PRAGMA temp_store = MEMORY;")
                    // 3. Memory-map up to 256MB of the DB directly into process virtual memory space
                    db.execSQL("PRAGMA mmap_size = 268435456;")
                    // 4. Increase WAL autocheckpoint interval to reduce flash storage writes during message generation
                    db.execSQL("PRAGMA wal_autocheckpoint = 2000;")
                } else if (totalRamGb >= 6L) {
                    // Mid/Standard-RAM (6-9GB) device optimizations:
                    // 1. Allocate 16MB RAM to SQLite page cache
                    db.execSQL("PRAGMA cache_size = -16384;")
                    // 2. Store temp tables and sorts in RAM
                    db.execSQL("PRAGMA temp_store = MEMORY;")
                    // 3. Memory-map up to 64MB of the DB
                    db.execSQL("PRAGMA mmap_size = 67108864;")
                    // 4. Moderate WAL autocheckpoint interval
                    db.execSQL("PRAGMA wal_autocheckpoint = 1000;")
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideChatDatabase(
        @ApplicationContext context: Context
    ): ChatDatabase {
        return Room.databaseBuilder(
            context,
            ChatDatabase::class.java,
            V1_DATABASE_NAME
        )
            .addCallback(createPragmaCallback(context))
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideChatRoomDao(database: ChatDatabase): ChatRoomDao {
        return database.chatRoomDao()
    }

    @Provides
    fun provideMessageDao(database: ChatDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideChatDatabaseV2(
        @ApplicationContext context: Context
    ): ChatDatabaseV2 {
        return Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            V2_DATABASE_NAME
        )
            .addCallback(createPragmaCallback(context))
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_10_11,
                ChatDatabaseV2Migrations.MIGRATION_11_12,
                ChatDatabaseV2Migrations.MIGRATION_12_13
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideChatRoomV2Dao(database: ChatDatabaseV2): ChatRoomV2Dao {
        return database.chatRoomDao()
    }

    @Provides
    fun provideMessageV2Dao(database: ChatDatabaseV2): MessageV2Dao {
        return database.messageDao()
    }

    @Provides
    fun providePlatformV2Dao(database: ChatDatabaseV2): PlatformV2Dao {
        return database.platformDao()
    }

    @Provides
    fun provideChatPlatformModelV2Dao(database: ChatDatabaseV2): ChatPlatformModelV2Dao {
        return database.chatPlatformModelDao()
    }

    @Provides
    fun provideAgentRunDao(database: ChatDatabaseV2): AgentRunDao {
        return database.agentRunDao()
    }

    @Provides
    fun provideAgentPersistenceDao(database: ChatDatabaseV2): AgentPersistenceDao {
        return database.agentPersistenceDao()
    }

    @Provides
    fun provideToolConnectionDao(database: ChatDatabaseV2): ToolConnectionDao {
        return database.toolConnectionDao()
    }

    @Provides
    fun provideLocalModelDao(database: ChatDatabaseV2): LocalModelDao {
        return database.localModelDao()
    }
}
