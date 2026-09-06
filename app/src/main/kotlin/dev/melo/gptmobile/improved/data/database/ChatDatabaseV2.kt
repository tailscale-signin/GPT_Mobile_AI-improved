package dev.melo.gptmobile.improved.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.database.dao.AgentPersistenceDao
import dev.melo.gptmobile.improved.data.database.dao.AgentRunDao
import dev.melo.gptmobile.improved.data.database.dao.ChatPlatformModelV2Dao
import dev.melo.gptmobile.improved.data.database.dao.ChatRoomV2Dao
import dev.melo.gptmobile.improved.data.database.dao.LocalModelDao
import dev.melo.gptmobile.improved.data.database.dao.MessageV2Dao
import dev.melo.gptmobile.improved.data.database.dao.PlatformV2Dao
import dev.melo.gptmobile.improved.data.database.dao.ToolConnectionDao
import dev.melo.gptmobile.improved.data.database.entity.AgentRun
import dev.melo.gptmobile.improved.data.database.entity.AgentToolBinding
import dev.melo.gptmobile.improved.data.database.entity.AssistantRevisionListConverter
import dev.melo.gptmobile.improved.data.database.entity.AssistantTimelineListConverter
import dev.melo.gptmobile.improved.data.database.entity.ChatAttachmentListConverter
import dev.melo.gptmobile.improved.data.database.entity.ChatPlatformModelV2
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.database.entity.StringListConverter
import dev.melo.gptmobile.improved.data.database.entity.ToolConnection
import dev.melo.gptmobile.improved.data.database.entity.ToolEvent

@Database(
    entities = [
        ChatRoomV2::class,
        MessageV2::class,
        PlatformV2::class,
        ChatPlatformModelV2::class,
        ToolConnection::class,
        AgentToolBinding::class,
        AgentRun::class,
        ToolEvent::class,
        LocalModel::class
    ],
    version = 13,
    exportSchema = true
)
@TypeConverters(
    StringListConverter::class,
    ChatAttachmentListConverter::class,
    AssistantRevisionListConverter::class,
    AssistantTimelineListConverter::class
)
abstract class ChatDatabaseV2 : RoomDatabase() {

    abstract fun platformDao(): PlatformV2Dao
    abstract fun chatRoomDao(): ChatRoomV2Dao
    abstract fun messageDao(): MessageV2Dao
    abstract fun chatPlatformModelDao(): ChatPlatformModelV2Dao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentPersistenceDao(): AgentPersistenceDao
    abstract fun toolConnectionDao(): ToolConnectionDao
    abstract fun localModelDao(): LocalModelDao
}
