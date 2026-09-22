package dev.melo.gptmobile.improved.data.repository.llama

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LlamaModelDao {
    @Insert(suppress = "RoomDatabaseQueryApi") suspend fun insert(model: LlamaModelInfo)
    @Query("SELECT * FROM llama_models WHERE id = :id")
    suspend fun getModelById(id: String): LlamaModelInfo?
    @Query("SELECT * FROM llama_models ORDER BY serverLoadPercent ASC")
    suspend fun getAllModels(): List<LlamaModelInfo>
    @Query("UPDATE llama_models SET isAvailable = 0 WHERE id = :id")
    suspend fun markUnavailable(id: String)
}