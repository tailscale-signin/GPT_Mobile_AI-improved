package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint
import dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** A no-send preflight can save a wait before the delivery session creates its first cursor. */
internal object CombinedAppendPreflight {
    suspend fun save(
        dao: AgentPersistenceDao,
        run: AgentRun,
        frozen: CombinedInputSnapshot,
        decision: ProviderLimitDecision,
        maxCharacters: Int
    ): CombinedDeliveryCheckpoint? {
        if (!dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS || !decision.knownNotAccepted || decision.category !in setOf(FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE.name, FreeFailureCategory.LOCAL_ALLOWANCE_EXHAUSTED.name)) return null
        if (run.status != "RUNNING" || run.profileUid != decision.profileUid || run.modelSnapshot != decision.model || frozen.profileUid != decision.profileUid || frozen.model != decision.model) return null
        if (frozen.combined?.requested?.appendContinuations != true || frozen.deliveryProtocol != "PASSAGES_V3") return null
        require(run.synthesisSnapshot == frozen.encode() && run.synthesisKey == researchHash(requireNotNull(run.synthesisSnapshot))) { "COMBINED_APPEND_SNAPSHOT_CHANGED" }
        frozen.bindAcceptedStage()
        requireNotNull(frozen.writerRequestPlan).validate(frozen)
        val revision = frozen.liveRevisionId(run.runId)
        val store = CombinedRecordStore.open(dao, run.runId, frozen, revision, maxCharacters, resume = true)
        val state = store.protocol.checkpoint()
        val pristine = state.lastAttemptOutcome == null && state.records.isEmpty() && state.pending.isEmpty() && state.recoveredFragments.isEmpty() && state.failure == null && state.recoveryAttempts == 0 && state.noProgressAttempts == 0
        val knownBoundary = state.appendReady && state.lastAttemptOutcome in setOf("COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION")
        // A later local cooldown says nothing about an earlier request whose completion
        // is unknown. Keep that cursor intact and require an explicit review.
        if (state.ended || state.noProgressAttempts >= 2 || !pristine && !knownBoundary) return null
        store.protocol.markAttemptFinished("KNOWN_PROVIDER_REJECTION", decision.retryAt)
        store.save(store.protocol.checkpoint())
        return dao.combinedDeliveryCheckpoint(run.runId, revision)
    }
}
