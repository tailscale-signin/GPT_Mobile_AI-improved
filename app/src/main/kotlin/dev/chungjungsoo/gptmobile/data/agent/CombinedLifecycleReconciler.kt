package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Repair the Room/budget commit gap without dispatching or treating unknown usage as free. */
class CombinedLifecycleReconciler @Inject constructor(
    private val runs: AgentRunDao,
    private val messages: AgentPersistenceDao,
    private val budgets: CombinedTurnBudgetStore
) {
    suspend fun reconcile() {
        runs.combinedTerminalOwners().forEach { run ->
            try {
                val accepted = CombinedAcceptedTurn.context(run.synthesisSnapshot)?.takeIf { it.plan != null } ?: return@forEach
                val message = if (run.synthesisKey != null) messages.restoreCombinedCandidate(run.runId) else messages.recoveryMessage(run.assistantMessageId)
                message ?: return@forEach
                val budget = budgets.open(accepted.copy(chatId = run.chatId))
                val owner = if (run.synthesisKey != null) "finalization" else "contributor:${run.profileUid}"
                budget.snapshot().reservations.values.filter { it.ownerKey == owner && it.status == CombinedReservationStatus.RESERVED }.forEach {
                    budget.unknown(it.attemptId, "Terminal run recovered; dispatch or measured usage has not been reconciled.")
                }
                val quality = message.timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
                val delivered = run.status == "COMPLETED" && quality?.let { it.complete && !it.correctionRequired && it.answerDigest == researchHash(message.content) && it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") } == true
                val outcome = when {
                    run.status == "CANCELED" -> "CANCELLED"
                    owner == "finalization" && run.status == "COMPLETED" && !delivered -> "PARTIAL"
                    else -> run.status
                }
                budget.finishStage(owner, outcome, run.terminalError)
                if (owner == "finalization" && delivered) budget.close("COMPLETED", quality?.deliveredScopeStatus)
                if (message.timeline.any { it.combinedFallback?.kind == "USE_REPLY" }) budget.close("PARTIAL", "EXPLICIT_CONTRIBUTOR_FALLBACK")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Combined", "RECONCILIATION_PENDING · run=${run.runId} · reason=${failure.javaClass.simpleName}", "W")
            }
        }
    }
}
