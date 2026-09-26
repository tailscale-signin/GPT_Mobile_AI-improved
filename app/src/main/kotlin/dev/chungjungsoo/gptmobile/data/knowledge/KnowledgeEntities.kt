package dev.chungjungsoo.gptmobile.data.knowledge

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2

@Entity(tableName = "knowledge_projects")
data class KnowledgeProject(@PrimaryKey val id: String, val name: String, val instructions: String = "")

@Entity(
    tableName = "knowledge_project_chats",
    foreignKeys = [
        ForeignKey(entity = KnowledgeProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("projectId")]
)
data class KnowledgeProjectChat(@PrimaryKey val chatId: Int, val projectId: String)

@Entity(
    tableName = "knowledge_documents",
    foreignKeys = [
        ForeignKey(entity = KnowledgeProject::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("projectId"), Index("chatId")]
)
data class KnowledgeDocument(
    @PrimaryKey val id: String,
    val title: String,
    val hash: String,
    val projectId: String? = null,
    val chatId: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "0") val deleted: Boolean = false
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(entity = KnowledgeDocument::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("documentId")]
)
data class KnowledgeChunk(
    @PrimaryKey val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String
)
