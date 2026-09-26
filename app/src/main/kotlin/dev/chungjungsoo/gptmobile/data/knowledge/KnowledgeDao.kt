package dev.chungjungsoo.gptmobile.data.knowledge

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Upsert suspend fun saveProject(project: KnowledgeProject)

    @Upsert suspend fun attachChat(link: KnowledgeProjectChat)

    @Query("DELETE FROM knowledge_project_chats WHERE chatId = :chatId")
    suspend fun detachChat(chatId: Int)

    @Query("SELECT * FROM knowledge_projects ORDER BY name")
    fun projects(): Flow<List<KnowledgeProject>>

    @Query("SELECT p.* FROM knowledge_projects p JOIN knowledge_project_chats c ON c.projectId = p.id WHERE c.chatId = :chatId")
    suspend fun projectForChat(chatId: Int): KnowledgeProject?

    @Query("SELECT * FROM knowledge_project_chats")
    fun chatLinks(): Flow<List<KnowledgeProjectChat>>

    @Query("DELETE FROM knowledge_projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    @Query("SELECT * FROM knowledge_documents WHERE deleted = 0 AND chatId IS NOT NULL ORDER BY updatedAt DESC")
    fun documents(): Flow<List<KnowledgeDocument>>

    @Query("SELECT * FROM knowledge_documents WHERE id = :id")
    suspend fun document(id: String): KnowledgeDocument?

    @Query("UPDATE knowledge_documents SET deleted = 1, title = '', hash = '' WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Transaction suspend fun deleteDocument(id: String) {
        deleteChunks(id)
        markDeleted(id)
    }

    @Upsert suspend fun saveDocument(document: KnowledgeDocument)

    @Upsert suspend fun saveChunks(chunks: List<KnowledgeChunk>)

    @Query("DELETE FROM knowledge_chunks WHERE documentId = :id")
    suspend fun deleteChunks(id: String)

    @Query("SELECT * FROM knowledge_chunks WHERE documentId = :id ORDER BY chunkIndex")
    suspend fun chunks(id: String): List<KnowledgeChunk>

    @Query("SELECT c.* FROM knowledge_chunks c JOIN knowledge_documents d ON d.id = c.documentId WHERE d.deleted = 0 AND (d.chatId = :chatId OR d.projectId = :projectId) ORDER BY d.updatedAt DESC, c.chunkIndex LIMIT 2048")
    suspend fun scopedChunks(chatId: Int, projectId: String?): List<KnowledgeChunk>

    @Query("SELECT c.* FROM knowledge_chunks c JOIN knowledge_documents d ON d.id = c.documentId WHERE d.deleted = 0 AND d.chatId IS NOT NULL ORDER BY d.updatedAt DESC, c.chunkIndex LIMIT 8192")
    suspend fun memoryChunks(): List<KnowledgeChunk>

    @Query("SELECT c.* FROM knowledge_chunks c JOIN knowledge_documents d ON d.id = c.documentId WHERE d.deleted = 0 AND d.chatId IS NOT NULL AND (:chatId IS NULL OR d.chatId = :chatId) AND (c.text LIKE :pattern OR d.title LIKE :pattern) ORDER BY d.updatedAt DESC, c.chunkIndex LIMIT 512")
    suspend fun matchingMemoryChunks(pattern: String, chatId: Int?): List<KnowledgeChunk>

    @Transaction suspend fun replaceDocument(document: KnowledgeDocument, chunks: List<KnowledgeChunk>, explicitlyRestore: Boolean = false) {
        val previous = this.document(document.id)
        if ((previous?.deleted == true && !explicitlyRestore) || (previous?.deleted != true && previous?.hash == document.hash)) return
        saveDocument(document)
        deleteChunks(document.id)
        saveChunks(chunks)
    }
}
