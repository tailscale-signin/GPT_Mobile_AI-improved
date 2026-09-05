package dev.chungjungsoo.gptmobile.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.chungjungsoo.gptmobile.data.database.converter.Converters
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

@Database(
    entities = [ChatRoomV2::class, MessageV2::class],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ChatDatabaseV2 : RoomDatabase() {
    abstract fun chatRoomV2Dao(): ChatRoomV2Dao
    abstract fun messageV2Dao(): MessageV2Dao
}
