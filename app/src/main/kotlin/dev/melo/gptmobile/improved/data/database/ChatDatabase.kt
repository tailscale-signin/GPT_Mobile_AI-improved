package dev.melo.gptmobile.improved.data.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.database.dao.ChatRoomDao
import dev.melo.gptmobile.improved.data.database.dao.MessageDao
import dev.melo.gptmobile.improved.data.database.entity.APITypeConverter
import dev.melo.gptmobile.improved.data.database.entity.ChatRoom
import dev.melo.gptmobile.improved.data.database.entity.Message

@Database(
    entities = [ChatRoom::class, Message::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
@TypeConverters(APITypeConverter::class)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatRoomDao(): ChatRoomDao
    abstract fun messageDao(): MessageDao
}
