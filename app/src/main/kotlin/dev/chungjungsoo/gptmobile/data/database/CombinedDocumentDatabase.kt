package dev.chungjungsoo.gptmobile.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chungjungsoo.gptmobile.data.agent.CombinedCheckpointPayloads
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocuments
import dev.chungjungsoo.gptmobile.data.agent.CombinedPayload
import dev.chungjungsoo.gptmobile.data.agent.CombinedStoredFields
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Backup validation streams bounded pages and fails before any destination rows are replaced. */
internal object CombinedDocumentDatabase {
    /** Conversation-only backups keep their actual bodies, without unrelated execution history. */
    fun retainPublishedOnly(database: SupportSQLiteDatabase) {
        val hasDocuments = database.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='combined_final_manifests'").use { it.moveToFirst() }
        if (!hasDocuments) return
        val references = references(database)
        database.execSQL("CREATE TEMP TABLE combined_keep_manifests (id TEXT PRIMARY KEY)")
        database.execSQL("CREATE TEMP TABLE combined_keep_payloads (runId TEXT, payloadId TEXT, PRIMARY KEY(runId, payloadId))")
        try {
            references.keys.forEach { database.execSQL("INSERT INTO combined_keep_manifests (id) VALUES (?)", arrayOf(it)) }
            database.execSQL("DELETE FROM combined_final_manifests WHERE id NOT IN (SELECT id FROM combined_keep_manifests)")
            fun retain(runId: String, raw: String) {
                CombinedPayload.storageIds(CombinedPayload.reference(raw)).forEach { id ->
                    database.execSQL("INSERT OR IGNORE INTO combined_keep_payloads (runId, payloadId) VALUES (?, ?)", arrayOf(runId, id))
                }
            }
            database.query("SELECT runId, bodyPayload, metadataPayload FROM combined_final_manifests").use { manifests ->
                while (manifests.moveToNext()) {
                    val runId = manifests.getString(0)
                    retain(runId, manifests.getString(1))
                    retain(runId, manifests.getString(2))
                    val fields = readMetadata(database, runId, manifests.getString(2))
                    fields.timelineText.values.forEach { retain(runId, it) }
                    fields.sources.mapNotNull { it.originalDocument }.forEach { source ->
                        require(source.runId == runId) { "Backup contains a mismatched Combined source owner." }
                        retain(runId, CombinedDocuments.sourcePayload(source).encode())
                        source.framePayloads.forEach { retain(runId, it) }
                    }
                }
            }
            database.query("SELECT m.runId, b.attributionPayload FROM combined_final_block_index b JOIN combined_final_manifests m ON m.id=b.manifestId").use { blocks ->
                while (blocks.moveToNext()) retain(blocks.getString(0), blocks.getString(1))
            }
            database.execSQL("DELETE FROM combined_durable_blocks WHERE NOT EXISTS (SELECT 1 FROM combined_keep_payloads p WHERE p.runId=combined_durable_blocks.runId AND p.payloadId=combined_durable_blocks.payloadId)")
            database.execSQL("DELETE FROM agent_runs WHERE run_id NOT IN (SELECT runId FROM combined_final_manifests)")
            // The owning IDs/fences are part of the document. Provider request snapshots,
            // tool events and active resumptions remain an Agent history selection.
            database.execSQL("UPDATE agent_runs SET synthesis_snapshot=NULL, synthesis_key=NULL")
        } finally {
            database.execSQL("DROP TABLE combined_keep_payloads")
            database.execSQL("DROP TABLE combined_keep_manifests")
        }
    }

    fun validateReferences(database: SupportSQLiteDatabase) {
        val hasDocuments = database.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='combined_final_manifests'").use { it.moveToFirst() }
        if (!hasDocuments) return
        val references = references(database)
        references.forEach { (id, saved) ->
            val (messageId, reference) = saved
            database.query("SELECT runId, finalMessageId, revisionId, bodyPayload, metadataPayload, blockMetadataPayload, bodyLength, blockCount, bodyDigest, metadataDigest FROM combined_final_manifests WHERE id=?", arrayOf(id)).use { manifest ->
                require(manifest.moveToFirst()) { "Backup is missing a Combined answer manifest." }
                val runId = manifest.getString(0)
                require(runId == reference.runId && manifest.getInt(1) == messageId && manifest.getString(2) == reference.revisionId && manifest.getInt(6) == reference.bodyLength && manifest.getInt(7) == reference.blockCount && manifest.getString(8) == reference.bodyDigest) { "Backup contains a mismatched Combined answer reference." }
                val body = CombinedPayload.reference(manifest.getString(3))
                require(body.count == reference.blockCount && body.length == reference.bodyLength && body.digest == reference.bodyDigest) { "Backup contains a mismatched Combined body." }
                validatePayload(database, runId, manifest.getString(3))
                val metadata = CombinedPayload.reference(manifest.getString(4))
                require(metadata.digest == manifest.getString(9)) { "Backup contains mismatched Combined metadata." }
                val fields = readMetadata(database, runId, manifest.getString(4))
                fields.timelineText.forEach { (index, payload) ->
                    require(index in fields.timeline.indices && fields.timeline[index].type == dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.TEXT) { "Backup contains an invalid Combined timeline reference." }
                    validatePayload(database, runId, payload)
                }
                fields.sources.mapNotNull { it.originalDocument }.forEach { source ->
                    require(source.runId == runId) { "Backup contains a mismatched Combined source owner." }
                    validatePayload(database, runId, CombinedDocuments.sourcePayload(source).encode())
                    source.framePayloads.forEach { validatePayload(database, runId, it) }
                }
                val indexes = StringBuilder()
                var expected = 0
                var end = 0
                database.query("SELECT sequence, start, end, textDigest, attributionPayload, digest FROM combined_final_block_index WHERE manifestId=? ORDER BY sequence", arrayOf(id)).use { blocks ->
                    while (blocks.moveToNext()) {
                        require(blocks.getInt(0) == expected && blocks.getInt(1) == end && blocks.getInt(2) > end) { "Backup contains a broken Combined block index." }
                        val digest = researchHash(listOf(blocks.getInt(0).toString(), blocks.getInt(1).toString(), blocks.getInt(2).toString(), blocks.getString(3), blocks.getString(4)).joinToString("\u0000"))
                        require(digest == blocks.getString(5)) { "Backup contains damaged Combined provenance." }
                        if (expected > 0) indexes.append('\n')
                        indexes.append(digest)
                        validatePayload(database, runId, blocks.getString(4))
                        end = blocks.getInt(2)
                        expected++
                    }
                }
                require(expected == reference.blockCount && end == reference.bodyLength && researchHash(indexes.toString()) == manifest.getString(5)) { "Backup contains incomplete Combined blocks." }
            }
        }
        database.query("SELECT runId, payloadsJson FROM combined_delivery_checkpoints WHERE storageVersion=2").use { checkpoints ->
            while (checkpoints.moveToNext()) {
                val payloads = Json.decodeFromString<CombinedCheckpointPayloads>(checkpoints.getString(1))
                validatePayload(database, checkpoints.getString(0), payloads.snapshot)
                validatePayload(database, checkpoints.getString(0), payloads.tail)
            }
        }
    }

    private fun references(database: SupportSQLiteDatabase): Map<String, Pair<Int, CombinedManifestReference>> {
        val references = linkedMapOf<String, Pair<Int, CombinedManifestReference>>()
        fun retain(messageId: Int, reference: CombinedManifestReference) {
            val owner = messageId to reference
            val prior = references.putIfAbsent(reference.id, owner)
            require(prior == null || prior == owner) { "Backup contains conflicting Combined manifest owners or references." }
        }
        database.query("SELECT message_id, combined_manifest, revisions FROM messages_v2").use { messages ->
            while (messages.moveToNext()) {
                val messageId = messages.getInt(0)
                if (!messages.isNull(1)) {
                    val reference = Json.decodeFromString<CombinedManifestReference>(messages.getString(1))
                    retain(messageId, reference)
                }
                AssistantRevisionListConverter().fromString(messages.getString(2)).forEach { revision ->
                    revision.combinedManifest?.let { retain(messageId, it) }
                }
            }
        }
        return references
    }

    private fun readMetadata(database: SupportSQLiteDatabase, runId: String, raw: String): CombinedStoredFields {
        val text = StringBuilder()
        validatePayload(database, runId, raw, text)
        return Json.decodeFromString(text.toString())
    }

    private fun validatePayload(database: SupportSQLiteDatabase, runId: String, raw: String, contents: StringBuilder? = null) {
        val reference = CombinedPayload.reference(raw)
        val digest = MessageDigest.getInstance("SHA-256")
        var sequence = 0
        var end = 0
        var previous: String? = null
        if (reference.storageVersion == 2) {
            reference.blocks.chunked(CombinedPayload.PAGE_BATCH).forEach { batch ->
                val ids = batch.map { it.payloadId }.distinct()
                val stored = linkedMapOf<String, dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock>()
                val placeholders = ids.joinToString(",") { "?" }
                database.query("SELECT payloadId, sequence, start, text, digest, previousDigest, utf8Bytes FROM combined_durable_blocks WHERE runId=? AND payloadId IN ($placeholders) AND sequence=0", arrayOf(runId) + ids.toTypedArray()).use { pages ->
                    while (pages.moveToNext()) {
                        val row = dev.chungjungsoo.gptmobile.data.database.entity.CombinedDurableBlock(runId, pages.getString(0), pages.getInt(1), pages.getInt(2), pages.getString(3), pages.getString(4), if (pages.isNull(5)) null else pages.getString(5), pages.getInt(6))
                        stored[row.payloadId] = row
                    }
                }
                batch.forEach { expected ->
                    val row = requireNotNull(stored[expected.payloadId]) { "Backup is missing Combined content." }
                    CombinedPayload.verifySharedBlock(runId, expected, row)
                    digest.update(row.text.toByteArray(Charsets.UTF_8))
                    contents?.append(row.text)
                    sequence++
                    end += row.text.length
                }
            }
        } else {
            database.query("SELECT sequence, start, text, digest, previousDigest, utf8Bytes FROM combined_durable_blocks WHERE runId=? AND payloadId=? ORDER BY sequence", arrayOf(runId, reference.id)).use { pages ->
                while (pages.moveToNext()) {
                    val text = pages.getString(2)
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val previousDigest = if (pages.isNull(4)) null else pages.getString(4)
                    require(pages.getInt(0) == sequence && pages.getInt(1) == end && text.isNotEmpty() && text.length <= CombinedPayload.PAGE_CHARACTERS && researchHash(text) == pages.getString(3) && previousDigest == previous && bytes.size == pages.getInt(5)) { "Backup contains damaged Combined content." }
                    digest.update(bytes)
                    contents?.append(text)
                    sequence++
                    end += text.length
                    previous = pages.getString(3)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(sequence == reference.count && end == reference.length && actual == reference.digest) { "Backup is missing Combined content." }
    }
}
