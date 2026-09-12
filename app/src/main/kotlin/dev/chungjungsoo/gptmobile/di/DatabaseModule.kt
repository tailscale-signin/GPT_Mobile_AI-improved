package dev.chungjungsoo.gptmobile.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatPlatformModelV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.LocalModelDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChatDatabaseV2 = Room
        .databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            "chats_v2.db"
        )
        .addMigrations(
            ChatDatabaseV2Migrations.MIGRATION_10_11,
            ChatDatabaseV2Migrations.MIGRATION_11_12,
            ChatDatabaseV2Migrations.MIGRATION_12_13,
            ChatDatabaseV2Migrations.MIGRATION_13_14,
            ChatDatabaseV2Migrations.MIGRATION_14_15,
            ChatDatabaseV2Migrations.MIGRATION_15_16,
            ChatDatabaseV2Migrations.MIGRATION_16_17,
            ChatDatabaseV2Migrations.MIGRATION_17_18,
            ChatDatabaseV2Migrations.MIGRATION_18_19
        )
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    fun provideChatRoomDao(database: ChatDatabaseV2): ChatRoomV2Dao = database.chatRoomDao()

    @Provides
    fun provideMessageDao(database: ChatDatabaseV2): MessageV2Dao = database.messageDao()

    @Provides
    fun providePlatformDao(database: ChatDatabaseV2): PlatformV2Dao = database.platformDao()

    @Provides
    fun provideChatPlatformModelDao(database: ChatDatabaseV2): ChatPlatformModelV2Dao = database.chatPlatformModelDao()

    @Provides
    fun provideAgentRunDao(database: ChatDatabaseV2): AgentRunDao = database.agentRunDao()

    @Provides
    fun provideAgentPersistenceDao(database: ChatDatabaseV2): AgentPersistenceDao = database.agentPersistenceDao()

    @Provides
    fun provideToolConnectionDao(database: ChatDatabaseV2): ToolConnectionDao = database.toolConnectionDao()

    @Provides
    fun provideLocalModelDao(database: ChatDatabaseV2): LocalModelDao = database.localModelDao()
}
