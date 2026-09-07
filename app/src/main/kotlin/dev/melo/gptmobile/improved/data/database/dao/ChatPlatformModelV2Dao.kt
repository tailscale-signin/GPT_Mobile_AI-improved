package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import dev.melo.gptmobile.improved.data.database.entity.ChatPlatformModelV2

@Dao
interface ChatPlatformModelV2Dao {

    @Query("SELECT * FROM chat_platform_models_v2 WHERE chat_id = :chatId")
    suspend fun getModelsByChatId(chatId: Int): List<ChatPlatformModelV2>

    @Upsert
    suspend fun upsert(model: ChatPlatformModelV2)

    @Upsert
    suspend fun upsertAll(models: List<ChatPlatformModelV2>)

    @Query("DELETE FROM chat_platform_models_v2 WHERE chat_id = :chatId AND platform_uid = :platformUid")
    suspend fun delete(chatId: Int, platformUid: String)

    @Query("DELETE FROM chat_platform_models_v2 WHERE chat_id = :chatId")
    suspend fun deleteAllByChatId(chatId: Int)
}
