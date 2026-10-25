package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryRecord
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalBlockIndex
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedFinalManifest

/** Shared by explicit recovery/history readers and the bounded UI block reader. */
interface CombinedDocumentDao {
    @Query("SELECT * FROM messages_v2 WHERE message_id=:messageId")
    suspend fun combinedDocumentOwner(messageId: Int): dev.chungjungsoo.gptmobile.data.database.entity.MessageV2?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCombinedDurableBlocks(blocks: List<CombinedDurableBlock>)

    @Query("SELECT * FROM combined_durable_blocks WHERE runId=:runId AND payloadId=:payloadId AND sequence>=:first ORDER BY sequence LIMIT :count")
    suspend fun combinedDurableBlockPage(runId: String, payloadId: String, first: Int, count: Int): List<CombinedDurableBlock>

    @Query("SELECT * FROM combined_durable_blocks WHERE runId=:runId AND payloadId IN (:payloadIds) AND sequence=0")
    suspend fun combinedSharedBlocks(runId: String, payloadIds: List<String>): List<CombinedDurableBlock>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCombinedDeliveryRecords(records: List<CombinedDeliveryRecord>)

    @Query("SELECT * FROM combined_delivery_records WHERE runId=:runId AND revisionId=:revisionId AND fence=:fence AND chainDigest=:chainDigest")
    suspend fun combinedDeliveryRecord(runId: String, revisionId: String, fence: Long, chainDigest: String): CombinedDeliveryRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCombinedFinalManifest(manifest: CombinedFinalManifest)

    @Query("SELECT * FROM combined_final_manifests WHERE id=:id")
    suspend fun combinedFinalManifest(id: String): CombinedFinalManifest?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCombinedFinalBlockIndexes(blocks: List<CombinedFinalBlockIndex>)

    @Query("SELECT * FROM combined_final_block_index WHERE manifestId=:manifestId AND sequence>=:first ORDER BY sequence LIMIT :count")
    suspend fun combinedFinalBlockIndexPage(manifestId: String, first: Int, count: Int): List<CombinedFinalBlockIndex>

    @Query("UPDATE combined_final_manifests SET committedAt=:committedAt WHERE id=:id AND ownershipFence=:fence AND committedAt=0")
    suspend fun markCombinedManifestCommitted(id: String, fence: Long, committedAt: Long): Int

    @Query("SELECT combined_final_manifests.* FROM combined_final_manifests JOIN agent_runs ON agent_runs.run_id=combined_final_manifests.runId WHERE agent_runs.chat_id=:chatId ORDER BY combined_final_manifests.id")
    suspend fun combinedFinalManifestsForChat(chatId: Int): List<CombinedFinalManifest>

    @Query("SELECT combined_durable_blocks.* FROM combined_durable_blocks JOIN agent_runs ON agent_runs.run_id=combined_durable_blocks.runId WHERE agent_runs.chat_id=:chatId ORDER BY combined_durable_blocks.runId, combined_durable_blocks.payloadId, combined_durable_blocks.sequence")
    suspend fun combinedDurableBlocksForChat(chatId: Int): List<CombinedDurableBlock>

    @Query("SELECT combined_final_block_index.* FROM combined_final_block_index JOIN combined_final_manifests ON combined_final_manifests.id=combined_final_block_index.manifestId JOIN agent_runs ON agent_runs.run_id=combined_final_manifests.runId WHERE agent_runs.chat_id=:chatId ORDER BY combined_final_block_index.manifestId, combined_final_block_index.sequence")
    suspend fun combinedFinalBlockIndexesForChat(chatId: Int): List<CombinedFinalBlockIndex>
}
