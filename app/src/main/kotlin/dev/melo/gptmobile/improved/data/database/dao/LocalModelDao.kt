package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.melo.gptmobile.improved.data.database.entity.LocalModel
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY display_name ASC")
    fun getAllModels(): Flow<List<LocalModel>>

    @Query("SELECT * FROM local_models WHERE id = :id")
    suspend fun getModelById(id: String): LocalModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: LocalModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<LocalModel>)

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun deleteModel(id: String)

    @Query("UPDATE local_models SET is_downloaded = :isDownloaded, download_progress = :progress WHERE id = :id")
    suspend fun updateDownloadStatus(id: String, isDownloaded: Boolean, progress: Float)
}
