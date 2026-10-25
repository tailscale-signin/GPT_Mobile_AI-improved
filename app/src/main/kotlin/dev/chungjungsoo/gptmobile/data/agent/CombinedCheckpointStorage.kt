package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.CombinedDocumentDao
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryRecord
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CombinedCheckpointPayloads(
    val snapshot: String,
    val tail: String,
    val previousRecordCount: Int,
    val previousRecordsDigest: String
)

@Serializable
internal data class CombinedCheckpointTail(val pending: String, val recoveredFragments: List<CombinedUncommittedFragment>)

@Serializable
internal data class CombinedRecordAttribution(
    val passages: List<CombinedPassageRecord>,
    val decisions: List<CombinedClaimReview>,
    val issues: List<String>
)

/** Restore is explicit; normal chat observation never expands a checkpoint or its source corpus. */
internal object CombinedCheckpointStorage {
    fun digest(row: CombinedDeliveryRecord): String = researchHash(
        listOf(row.previousChainDigest, row.sequence.toString(), row.recordId, row.wireDigest, row.answerPayload, row.attributionPayload, row.renderedStart.toString(), row.renderedEnd.toString()).joinToString("\u0000")
    )

    suspend fun restore(dao: CombinedDocumentDao, row: CombinedDeliveryCheckpoint): CombinedDeliveryCheckpoint {
        if (row.storageVersion == 1) return row
        require(row.storageVersion == 2 && row.recordCount >= 0 && row.snapshotJson.isEmpty() && row.renderedAnswer.isEmpty() && row.attributionJson.isEmpty()) { "COMBINED_CHECKPOINT_STORAGE" }
        val references = Json.decodeFromString<CombinedCheckpointPayloads>(row.payloadsJson)
        val snapshot = CombinedPayload.read(dao, row.runId, references.snapshot)
        val tail = Json.decodeFromString<CombinedCheckpointTail>(CombinedPayload.read(dao, row.runId, references.tail))
        val metadata = Json.decodeFromString<CombinedRecordCheckpoint>(row.stateJson)
        require(metadata.records.isEmpty() && metadata.pending.isEmpty() && metadata.recoveredFragments.isEmpty()) { "COMBINED_CHECKPOINT_METADATA" }
        val reversed = ArrayList<CombinedDeliveryRecord>(row.recordCount)
        var expected = row.recordsDigest
        var sequence = row.recordCount
        while (sequence > 0) {
            val record = requireNotNull(dao.combinedDeliveryRecord(row.runId, row.revisionId, row.fence, expected)) { "COMBINED_CHECKPOINT_RECORD_MISSING" }
            require(record.sequence == sequence && record.chainDigest == expected && record.chainDigest == digest(record)) { "COMBINED_CHECKPOINT_RECORD_CHAIN" }
            reversed += record
            expected = record.previousChainDigest
            sequence--
        }
        require(expected.isEmpty()) { "COMBINED_CHECKPOINT_RECORD_PREFIX" }
        val committed = ArrayList<CombinedCommittedRecord>(row.recordCount)
        val answer = StringBuilder()
        val passages = mutableListOf<CombinedPassageRecord>()
        val decisions = mutableListOf<CombinedClaimReview>()
        val issues = mutableListOf<String>()
        reversed.asReversed().forEach { record ->
            val wire = CombinedPayload.read(dao, row.runId, record.wirePayload)
            require(record.wireDigest == researchHash(wire) && record.renderedStart == answer.length) { "COMBINED_CHECKPOINT_RECORD_CONTENT" }
            committed += CombinedCommittedRecord(record.sequence, record.recordId, wire, record.wireDigest)
            answer.append(CombinedPayload.read(dao, row.runId, record.answerPayload))
            require(record.renderedEnd == answer.length) { "COMBINED_CHECKPOINT_RECORD_RANGE" }
            val attribution = Json.decodeFromString<CombinedRecordAttribution>(CombinedPayload.read(dao, row.runId, record.attributionPayload))
            passages += attribution.passages
            decisions += attribution.decisions
            issues += attribution.issues
        }
        val text = answer.toString()
        val report = CombinedQualityReport(answerDigest = researchHash(text), passages = passages, decisions = decisions, issues = issues, revisionId = row.revisionId, deliveredScopeStatus = "WRITING")
        require(passages.all { it.matches(text) && it.links.all { link -> link.matches(text) && link.start >= it.start && link.end <= it.end } }) { "COMBINED_CHECKPOINT_ATTRIBUTION" }
        return row.copy(
            snapshotJson = snapshot,
            stateJson = Json.encodeToString(metadata.copy(records = committed, pending = tail.pending, recoveredFragments = tail.recoveredFragments)),
            renderedAnswer = text,
            attributionJson = Json.encodeToString(report),
            storageVersion = 1,
            recordCount = 0,
            recordsDigest = "",
            payloadsJson = ""
        )
    }
}
