package dev.chungjungsoo.gptmobile.data.memory.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_store_state WHERE id = 1")
    suspend fun state(): MemoryStoreStateEntity?

    @Query("SELECT * FROM memory_facts WHERE enabled = 1 AND state = 'ACTIVE' AND scope = :scope AND (validFrom IS NULL OR validFrom <= :at) AND (validTo IS NULL OR validTo > :at) ORDER BY id LIMIT :limit")
    suspend fun currentFacts(scope: String, at: Long, limit: Int): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE id = :id")
    suspend fun fact(id: String): MemoryFactEntity?

    @Insert
    suspend fun insertFact(fact: MemoryFactEntity)

    @Insert
    suspend fun insertLink(link: MemoryFactLinkEntity)

    @Insert
    suspend fun insertPending(change: MemoryPendingChangeEntity)

    @Query("UPDATE memory_facts SET revision = :revision, payload = :payload, enabled = :enabled, state = :state, validFrom = :validFrom, validTo = :validTo WHERE id = :id AND revision = :expectedRevision")
    suspend fun updateVersion(id: String, expectedRevision: Long, revision: Long, payload: ByteArray, enabled: Boolean, state: String, validFrom: Long?, validTo: Long?): Int

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteFact(id: String)
}
