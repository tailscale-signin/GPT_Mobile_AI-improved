package dev.melo.gptmobile.improved.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.melo.gptmobile.improved.data.database.AppDatabase
import dev.melo.gptmobile.improved.data.database.dao.ChatPlatformModelV2Dao
import dev.melo.gptmobile.improved.data.database.dao.ChatV2Dao
import dev.melo.gptmobile.improved.data.database.dao.PlatformV2Dao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getInstance(context)

    @Provides
    fun provideChatDao(database: AppDatabase): ChatV2Dao = database.chatDao()

    @Provides
    fun providePlatformV2Dao(database: AppDatabase): PlatformV2Dao = database.platformV2Dao()

    @Provides
    fun provideChatPlatformModelV2Dao(database: AppDatabase): ChatPlatformModelV2Dao = database.chatPlatformModelV2Dao()
}
