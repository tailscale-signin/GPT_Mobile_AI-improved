package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedResearchStages
import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore
import dev.chungjungsoo.gptmobile.data.research.work.ScheduledExamination
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Drain accepted appraisal work before its contributor releases funding or the editor closes the root. */
class CombinedExaminationBarrier @Inject constructor(
    private val archive: ResearchArchiveRepository,
    private val coordinator: Provider<ArticleExaminationCoordinator>,
    private val budgets: CombinedTurnBudgetStore
) {
    suspend fun await(context: CombinedExecutionContext, profileUid: String, rootWorkId: String) {
        val stages = listOf("appraisal", "independent_review", "study_review").map { CombinedResearchStages.id(profileUid, it) }.filter { id -> context.plan?.workItems?.any { it.id == id } == true }
        if (stages.isEmpty()) return
        val budget = budgets.open(context)
        var complete = true
        var reason: String? = null
        try {
            val finished = withTimeoutOrNull(180_000L) {
                var cursor = ""
                while (true) {
                    val rows = archive.dao.pendingExaminations(cursor)
                    if (rows.isEmpty()) break
                    for (row in rows) {
                        cursor = row.id
                        val saved = Json.decodeFromString<ScheduledExamination>(row.operation)
                        if (saved.request.rootWorkId != rootWorkId || saved.tools.combined?.rootBudgetId != context.rootBudgetId || saved.tools.combined?.profileUid?.let { it != profileUid } == true) continue
                        if (saved.waitingReason != null) {
                            complete = false
                            reason = saved.waitingReason
                            continue
                        }
                        val id = coordinator.get().automaticExamine(saved.request) { saved.tools }
                        val result = coordinator.get().awaitExecution(id)
                        if (result == ExaminationStage.COMPLETE_FULL) {
                            archive.dao.acknowledge(row.id, row.createdAt)
                        } else {
                            complete = false
                            reason = "EXAMINATION_${result?.name ?: "UNAVAILABLE"}"
                        }
                    }
                }
                true
            }
            if (finished != true) {
                complete = false
                reason = "EXAMINATION_DELIVERY_DEADLINE_CHECKPOINT_RETAINED"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            complete = false
            reason = "EXAMINATION_BLOCKED_CHECKPOINT_RETAINED"
        }
        stages.forEach { budget.finishStage(it, if (complete) "COMPLETED" else "PARTIAL", reason) }
    }
}
