package dev.chungjungsoo.gptmobile.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import dev.chungjungsoo.gptmobile.data.local.AppDatabase
import dev.chungjungsoo.gptmobile.data.local.dao.ChatDao
import dev.chungjungsoo.gptmobile.data.local.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.local.dao.PromptDao
import dev.chungjungsoo.gptmobile.data.local.dao.RagDocumentChunkDao
import dev.chungjungsoo.gptmobile.data.local.dao.RagDocumentDao
import dev.chungjungsoo.gptmobile.data.local.dao.ToolDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "gpt_mobile_database"
    ).fallbackToDestructiveMigration().build()

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun providePromptDao(database: AppDatabase): PromptDao = database.promptDao()

    @Provides
    fun provideToolDao(database: AppDatabase): ToolDao = database.toolDao()

    @Provides
    fun provideRagDocumentDao(database: AppDatabase): RagDocumentDao = database.ragDocumentDao()

    @Provides
    fun provideRagDocumentChunkDao(database: AppDatabase): RagDocumentChunkDao = database.ragDocumentChunkDao()

    @Provides
    @Singleton
    fun provideChatDatabaseV2(
        @ApplicationContext context: Context
    ): ChatDatabaseV2 = Room.databaseBuilder(
        context,
        ChatDatabaseV2::class.java,
        "chat_database_v2"
    )
        .addMigrations(
            ChatDatabaseV2Migrations.MIGRATION_10_11,
            ChatDatabaseV2Migrations.MIGRATION_11_12,
            ChatDatabaseV2Migrations.MIGRATION_12_13
        )
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
            }
        })
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    fun providePlatformV2Dao(database: ChatDatabaseV2): PlatformV2Dao = database.platformDao()

    @Provides
    fun provideChatRoomV2Dao(database: ChatDatabaseV2): ChatRoomV2Dao = database.chatRoomDao()

    @Provides
    fun provideMessageV2Dao(database: ChatDatabaseV2): MessageV2Dao = database.messageDao()

    @Provides
    fun provideChatPlatformModelV2Dao(database: ChatDatabaseV2): ChatPlatformModelV2Dao = database.chatPlatformModelDao()

    @Provides
    fun provideAgentRunDao(database: ChatDatabaseV2): AgentRunDao = database.agentRunDao()

    @Provides
    fun provideAgentPersistenceDao(database: ChatDatabaseV2): AgentPersistenceDao = database.agentPersistenceDao()

    @Provides
    fun provideToolConnectionDao(database: ChatDatabaseV2): ToolConnectionDao = database.toolConnectionDao()

    @Provides
    fun provideLocalModelDao(database: ChatDatabaseV2): LocalModelDao = database.localModelDao()
}
