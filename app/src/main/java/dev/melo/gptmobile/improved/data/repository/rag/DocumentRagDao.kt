package dev.melo.gptmobile.improved.data.repository.rag

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentRagDao {
    @Insert(suppress = "RoomDatabaseQueryApi") suspend fun insert(index: DocumentIndex)
    @Query("SELECT * FROM document_indices WHERE id = :id")
    suspend fun getIndexById(id: String): DocumentIndex?
    @Query("SELECT * FROM document_indices")
    suspend fun getAllIndices(): List<DocumentIndex>
    @Query("DELETE FROM document_indices WHERE id = :id")
    suspend fun deleteIndex(id: String)
}