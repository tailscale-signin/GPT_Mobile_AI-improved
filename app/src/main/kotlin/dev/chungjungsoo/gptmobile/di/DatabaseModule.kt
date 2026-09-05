package dev.chungjungsoo.gptmobile.di

import android.content.Context
import androidx.room.Room
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
