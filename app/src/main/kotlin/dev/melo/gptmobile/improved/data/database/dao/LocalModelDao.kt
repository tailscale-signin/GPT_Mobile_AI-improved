package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models")
    fun getAll(): Flow<List<LocalModel>>

    @Query("SELECT * FROM local_models WHERE catalog_entry_id = :catalogEntryId LIMIT 1")
    suspend fun getByCatalogEntryId(catalogEntryId: String): LocalModel?

    @Query("SELECT * FROM local_models WHERE status = :status")
    fun getByStatus(status: String): Flow<List<LocalModel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModel)

    @Update
    suspend fun update(model: LocalModel)

    @Delete
    suspend fun delete(model: LocalModel)

    @Query("DELETE FROM local_models WHERE catalog_entry_id = :catalogEntryId")
    suspend fun deleteByCatalogEntryId(catalogEntryId: String)

    @Query("UPDATE local_models SET status = :status, updated_at = :updatedAt WHERE catalog_entry_id = :catalogEntryId")
    suspend fun updateStatus(catalogEntryId: String, status: String, updatedAt: Long)
}
