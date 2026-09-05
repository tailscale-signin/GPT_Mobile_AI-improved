package dev.chungjungsoo.gptmobile.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
}
