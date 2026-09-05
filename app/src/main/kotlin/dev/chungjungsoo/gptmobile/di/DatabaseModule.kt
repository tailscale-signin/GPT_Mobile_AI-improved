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
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "chat_database"

    @Provides
    @Singleton
    fun provideChatDatabaseV2(
        @ApplicationContext context: Context
    ): ChatDatabaseV2 {
        return Room.databaseBuilder(
            context,
            ChatDatabaseV2::class.java,
            DATABASE_NAME
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
}
