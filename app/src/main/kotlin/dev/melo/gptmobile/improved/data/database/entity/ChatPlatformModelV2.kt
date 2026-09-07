package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "chat_platform_models_v2",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["chat_id", "platform_uid"], unique = true)
    ]
)
@Serializable
data class ChatPlatformModelV2(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "platform_uid")
    val platformUid: String,

    @ColumnInfo(name = "model_name")
    val modelName: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
) {
    // Convenience constructor / getter matching v0.8.2 callers expecting (chatId, platformUid, model)
    constructor(chatId: Int, platformUid: String, model: String) : this(
        id = 0,
        chatId = chatId,
        platformUid = platformUid,
        modelName = model,
        updatedAt = System.currentTimeMillis() / 1000
    )

    val model: String get() = modelName
}
