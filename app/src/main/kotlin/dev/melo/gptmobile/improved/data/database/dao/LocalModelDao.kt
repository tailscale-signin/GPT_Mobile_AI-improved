package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import dev.melo.gptmobile.improved.data.localmodel.LocalModelStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY catalog_entry_id ASC")
    fun observeAll(): Flow<List<LocalModel>>

    @Query("SELECT * FROM local_models")
    suspend fun getAll(): List<LocalModel>

    @Query("SELECT * FROM local_models WHERE catalog_entry_id = :id")
    suspend fun getById(id: String): LocalModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<LocalModel>)

    @Query("DELETE FROM local_models WHERE catalog_entry_id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE local_models SET status = :status, updated_at = :updatedAt WHERE catalog_entry_id = :catalogEntryId")
    suspend fun updateStatus(catalogEntryId: String, status: LocalModelStatus, updatedAt: Long)
}
