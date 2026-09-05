package dev.chungjungsoo.gptmobile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.chungjungsoo.gptmobile.data.database.converter.ChatRoomConverter
import dev.chungjungsoo.gptmobile.data.database.converter.MessageConverter
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.ChatSharedMediaDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.PinMetadataDao
import dev.chungjungsoo.gptmobile.data.database.dao.SearchHistoryDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatSharedMedia
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PinMetadata
import dev.chungjungsoo.gptmobile.data.database.entity.SearchHistory

@Database(
    entities = [
        ChatRoomV2::class,
        MessageV2::class,
        SearchHistory::class,
        PinMetadata::class,
        ChatSharedMedia::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(ChatRoomConverter::class, MessageConverter::class)
abstract class ChatDatabaseV2 : RoomDatabase() {
    abstract fun chatRoomDao(): ChatRoomV2Dao
    abstract fun messageDao(): MessageV2Dao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun pinMetadataDao(): PinMetadataDao
    abstract fun chatSharedMediaDao(): ChatSharedMediaDao
}
