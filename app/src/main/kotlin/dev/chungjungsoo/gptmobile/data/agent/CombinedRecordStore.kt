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
    private val synthesisKey: String,
    private val frozen: CombinedInputSnapshot,
    private val renderer: CombinedPassageDecoder,
    val protocol: CombinedRecordProtocol,
    val restored: Boolean,
    private var generation: Long,
    private var renderedRecords: Int
) {
    private val frozenJson = frozen.encode()

    suspend fun save(state: CombinedRecordCheckpoint) {
        require(state.snapshotDigest == frozen.inputDigest)
        val (answer, quality) = withContext(Dispatchers.Default) {
            state.records.drop(renderedRecords).forEach { renderer.accept(it.wire + "\n") }
            renderedRecords = state.records.size
            renderer.answer() to CombinedQualityReport(
                answerDigest = researchHash(renderer.answer()),
                passages = renderer.passages.toList(),
                decisions = renderer.decisions.toList(),
                issues = renderer.issues.toList(),
                revisionId = state.revisionId,
                deliveredScopeStatus = "WRITING"
            )
        }
        val row = CombinedDeliveryCheckpoint(runId, state.revisionId, fence, Math.addExact(generation, 1), state.snapshotDigest, frozenJson, Json.encodeToString(state), answer, Json.encodeToString(quality), System.currentTimeMillis())
        check(dao.commitCombinedDeliveryCheckpoint(row, generation, synthesisKey)) { "COMBINED_RECORD_OWNER_CHANGED" }
        generation = row.generation
    }

    companion object {
        suspend fun open(dao: AgentPersistenceDao, runId: String, frozen: CombinedInputSnapshot, revisionId: String, maxCharacters: Int, resume: Boolean): CombinedRecordStore {
            require(frozen.deliveryProtocol == "PASSAGES_V3" && frozen.version >= 6 && frozen.inputDigest == frozen.contentDigest())
            val run = requireNotNull(dao.recoveryRun(runId))
            val existing = dao.combinedDeliveryCheckpoint(runId, revisionId)
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
                    (frozen.claimInventory?.claims ?: CombinedClaimSemantics.records(frozen.sources)).flatMap { it.candidateIds }.toSet(),
                    frozen.sources.indices.mapTo(linkedSetOf()) { "C${it + 1}" },
                    frozen.evidence.records.mapTo(linkedSetOf()) { it.id },
                    maxCharacters,
                    frozen.evidence.records.mapIndexed { index, record -> record.id to "[${index + 1}](${record.url})" }.toMap()
                )
                state.records.forEach { renderer.accept(it.wire + "\n") }
                retained?.let {
                    val quality = Json.decodeFromString<CombinedQualityReport>(it.attributionJson)
                    require(renderer.answer() == it.renderedAnswer && quality.answerDigest == researchHash(it.renderedAnswer) && quality.passages == renderer.passages) { "COMBINED_CHECKPOINT_RENDERING_CHANGED" }
                }
                protocol
            }
            return CombinedRecordStore(dao, runId, run.ownershipFence, requireNotNull(run.synthesisKey), frozen, renderer, protocol, retained != null, existing?.generation ?: 0, state.records.size).also {
                if (existing == null) it.save(protocol.checkpoint())
            }
        }
    }
}
