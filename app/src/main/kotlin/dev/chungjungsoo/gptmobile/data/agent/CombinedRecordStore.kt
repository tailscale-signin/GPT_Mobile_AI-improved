package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One owner and one compare-and-swap generation protect each candidate stream. */
internal class CombinedRecordStore private constructor(
    private val dao: AgentPersistenceDao,
    private val runId: String,
    private val fence: Long,
    private val expectedRunStatus: String,
    private val synthesisKey: String,
    private val frozen: CombinedInputSnapshot,
    private var renderer: CombinedPassageDecoder,
    private val maxCharacters: Int,
    val protocol: CombinedRecordProtocol,
    val restored: Boolean,
    private var generation: Long,
    private var renderedRecords: Int,
    private var persistedRecords: Int,
    private var recordsDigest: String,
    private var committedRecords: List<CombinedCommittedRecord>
) {
    private val frozenJson = frozen.encode()
    private var lastSavedState: CombinedRecordCheckpoint? = null
    private var snapshotPayload: String? = null
    private var rendererNeedsReplay = false

    suspend fun save(state: CombinedRecordCheckpoint) {
        require(state.snapshotDigest == frozen.inputDigest)
        require(state.records.take(committedRecords.size) == committedRecords) { "A committed record cannot be rewritten" }
        if (state == lastSavedState) return
        val previousCount = persistedRecords
        val previousDigest = recordsDigest
        try {
            // An old inline checkpoint, or an explicit successor, is normalized once.
            // Ordinary saves only render and write newly committed logical records.
            if (rendererNeedsReplay || persistedRecords == 0 && renderedRecords > 0) {
                renderer = withContext(Dispatchers.Default) { CombinedPassageDecoder(frozen.sources, frozen.evidence, maxCharacters, strictRecords = true) }
                withContext(Dispatchers.Default) { state.records.take(persistedRecords).forEach { renderer.accept(it.wire + "\n") } }
                renderedRecords = persistedRecords
                rendererNeedsReplay = false
            }
            for (record in state.records.drop(persistedRecords)) {
                val prepared = withContext(Dispatchers.Default) {
                    require(record.sequence == renderedRecords + 1 && record.digest == researchHash(record.wire)) { "COMBINED_CHECKPOINT_RECORD_IDENTITY" }
                    val start = renderer.answerLength
                    val passages = renderer.passages.size
                    val decisions = renderer.decisions.size
                    val issues = renderer.issues.size
                    renderer.accept(record.wire + "\n")
                    renderedRecords++
                    Triple(start, renderer.answerSince(start), CombinedRecordAttribution(renderer.passages.drop(passages), renderer.decisions.drop(decisions), renderer.issues.drop(issues)))
                }
                val wire = CombinedPayload.write(dao, runId, record.wire)
                val answer = CombinedPayload.write(dao, runId, prepared.second)
                val attribution = CombinedPayload.write(dao, runId, Json.encodeToString(prepared.third))
                val staged = dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryRecord(
                    runId, state.revisionId, fence, record.sequence, record.id, wire.encode(), record.digest,
                    answer.encode(), attribution.encode(), prepared.first, prepared.first + prepared.second.length, recordsDigest, ""
                ).let { it.copy(chainDigest = CombinedCheckpointStorage.digest(it)) }
                dao.insertCombinedDeliveryRecords(listOf(staged))
                require(dao.combinedDeliveryRecord(runId, state.revisionId, fence, staged.chainDigest) == staged) { "COMBINED_CHECKPOINT_RECORD_REWRITE" }
                recordsDigest = staged.chainDigest
                persistedRecords++
            }
            val savedSnapshot = snapshotPayload ?: CombinedPayload.write(dao, runId, frozenJson).encode().also { snapshotPayload = it }
            val tailPayload = CombinedPayload.write(dao, runId, Json.encodeToString(CombinedCheckpointTail(state.pending, state.recoveredFragments)))
            val row = CombinedDeliveryCheckpoint(
                runId, state.revisionId, fence, Math.addExact(generation, 1), state.snapshotDigest, "",
                Json.encodeToString(state.copy(records = emptyList(), pending = "", recoveredFragments = emptyList())), "", "", System.currentTimeMillis(),
                storageVersion = 2, recordCount = persistedRecords, recordsDigest = recordsDigest,
                payloadsJson = Json.encodeToString(CombinedCheckpointPayloads(savedSnapshot, tailPayload.encode(), previousCount, previousDigest))
            )
            check(dao.commitCombinedDeliveryCheckpoint(row, generation, synthesisKey, expectedRunStatus)) { "COMBINED_RECORD_OWNER_CHANGED" }
            generation = row.generation
            committedRecords = state.records
            lastSavedState = state
        } catch (failure: Throwable) {
            persistedRecords = previousCount
            recordsDigest = previousDigest
            rendererNeedsReplay = true
            throw failure
        }
    }

    companion object {
        suspend fun open(dao: AgentPersistenceDao, runId: String, frozen: CombinedInputSnapshot, revisionId: String, maxCharacters: Int, resume: Boolean, expectedRunStatus: String = "RUNNING"): CombinedRecordStore {
            require(expectedRunStatus in setOf("RUNNING", "WAITING_REMOTE_JOB"))
            require(frozen.deliveryProtocol == "PASSAGES_V3" && frozen.version >= 6 && frozen.inputDigest == frozen.contentDigest())
            val run = requireNotNull(dao.recoveryRun(runId))
            val rawExisting = dao.rawCombinedDeliveryCheckpoint(runId, revisionId)
            val existing = rawExisting?.let { CombinedCheckpointStorage.restore(dao, it) }
            val previous = if (existing == null && resume && frozen.resumeFromRunId != null) {
                dao.combinedDeliveryCheckpoint(requireNotNull(frozen.resumeFromRunId), requireNotNull(frozen.deliveryResumeFromRevisionId))
                    ?: error("COMBINED_CHECKPOINT_UNAVAILABLE: A new explicit revision is required; saved prose was retained.")
            } else {
                null
            }
            val predecessor = if (existing == null && previous == null && resume && frozen.predecessorRunId != null) {
                val priorRun = requireNotNull(dao.recoveryRun(requireNotNull(frozen.predecessorRunId)))
                val raw = requireNotNull(priorRun.synthesisSnapshot)
                require(researchHash(raw) == priorRun.synthesisKey) { "COMBINED_CHECKPOINT_INTEGRITY" }
                val original = Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(raw)
                CombinedSuccessorSnapshot.validate(original, frozen, priorRun.runId)
                dao.combinedDeliveryCheckpoint(priorRun.runId, original.liveRevisionId(priorRun.runId))?.also {
                    require(it.snapshotDigest == original.inputDigest && it.snapshotJson == raw) { "COMBINED_CHECKPOINT_INTEGRITY" }
                }
            } else {
                null
            }
            val retained = existing ?: previous ?: predecessor
            val savedState = retained?.let { Json.decodeFromString<CombinedRecordCheckpoint>(it.stateJson) }
            require(retained == null || retained.snapshotDigest == savedState?.snapshotDigest && retained.revisionId == savedState.revisionId)
            val state = if (predecessor != null) {
                requireNotNull(savedState).copy(snapshotDigest = frozen.inputDigest, revisionId = revisionId, recoveryAttempts = 0, malformedRecoveryAttempts = 0)
            } else {
                savedState ?: CombinedRecordCheckpoint(snapshotDigest = frozen.inputDigest, revisionId = revisionId)
            }
            require(state.snapshotDigest == frozen.inputDigest)
            val renderer = CombinedPassageDecoder(frozen.sources, frozen.evidence, maxCharacters, strictRecords = true)
            val protocol = withContext(Dispatchers.Default) {
                val protocol = CombinedRecordProtocol(
                    state.copy(revisionId = revisionId),
                    (frozen.claimInventory?.claims ?: CombinedClaimSemantics.records(frozen.sources)).flatMap { it.candidateIds + it.id + it.canonicalId }.toSet(),
                    frozen.sources.indices.mapTo(linkedSetOf()) { "C${it + 1}" },
                    frozen.evidence.records.mapTo(linkedSetOf()) { it.id },
                    maxCharacters,
                    frozen.evidence.records.mapIndexed { index, record -> record.id to "[${index + 1}](${record.url})" }.toMap(),
                    continuationAnswerPolicy = frozen.recordContinuationAnswerPolicy()
                )
                state.records.forEach { renderer.accept(it.wire + "\n") }
                retained?.let {
                    val quality = Json.decodeFromString<CombinedQualityReport>(it.attributionJson)
                    // Persisted attribution belongs to the algorithm that saved this revision.
                    // Reopening can improve links without changing a single delivered character.
                    // Validate the archived ranges and owners, then derive current links from
                    // the same immutable records; never require algorithm-dependent equality.
                    val owners = frozen.sources.mapTo(hashSetOf()) { source -> source.platformUid }
                    require(
                        renderer.answer() == it.renderedAnswer &&
                            quality.answerDigest == researchHash(it.renderedAnswer) &&
                            quality.passages.all { passage ->
                                passage.matches(it.renderedAnswer) &&
                                    owners.containsAll(passage.profileUids) &&
                                    passage.links.all { link ->
                                        link.matches(it.renderedAnswer) && link.start >= passage.start && link.end <= passage.end && owners.containsAll(link.profileUids)
                                    }
                            }
                    ) { "COMBINED_CHECKPOINT_RENDERING_CHANGED" }
                }
                protocol
            }
            return CombinedRecordStore(
                dao, runId, run.ownershipFence, expectedRunStatus, requireNotNull(run.synthesisKey), frozen, renderer, maxCharacters, protocol,
                retained != null, existing?.generation ?: 0, state.records.size,
                rawExisting?.takeIf { it.storageVersion == 2 }?.recordCount ?: 0,
                rawExisting?.takeIf { it.storageVersion == 2 }?.recordsDigest.orEmpty(),
                if (existing != null) state.records else emptyList()
            ).also {
                if (existing == null) it.save(protocol.checkpoint())
            }
        }
    }
}
