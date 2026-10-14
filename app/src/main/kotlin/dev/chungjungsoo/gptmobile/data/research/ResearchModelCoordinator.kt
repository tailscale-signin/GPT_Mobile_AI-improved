package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.tool.parseDelegationObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
internal enum class ResearchModelStage { PLAN, FIND_GAPS, EXTRACT, REVIEW }

@Serializable
data class ResearchModelEvent(
    val id: String,
    val stage: String,
    val profileUid: String,
    val model: String,
    val local: Boolean,
    val outcome: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val invocationId: String? = null,
    val inputTokensEstimate: Int = 0,
    val outputTokensEstimate: Int = 0,
    val reason: String = "",
    val independent: Boolean = false
)

internal data class ResearchModelReply(val text: String, val modelKey: String, val invocationId: String? = null, val independent: Boolean = false)

/** Only pinned, consent-checked endpoints supplied by the parent are ever considered. */
internal data class ResearchModelEndpoint(
    val profileUid: String,
    val model: String,
    val modelKey: String,
    val local: Boolean,
    val available: suspend () -> Boolean,
    val timeoutMillis: Long? = null,
    val generate: suspend (ResearchModelStage, String, Int) -> ResearchModelReply
)

/** Local-first, no tools, no recursive delegation, and no silent cross-account fallback. */
internal class ResearchModelCoordinator(
    private val workers: List<ResearchModelEndpoint>,
    private val reviewer: ResearchModelEndpoint? = null,
    private val stillEnabled: suspend () -> Boolean = { true },
    private val deadlineMillis: Long = ResearchTimeouts.MODEL_MS,
    private val onEvent: suspend (ResearchModelEvent) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val failures = mutableMapOf<String, Int>()
    private val history = mutableListOf<ResearchModelEvent>()
    val events: List<ResearchModelEvent> get() = history.toList()

    private suspend fun record(event: ResearchModelEvent) {
        history.removeAll { it.id == event.id }
        history += event
        if (history.size > 200) history.removeAt(0)
        onEvent(event)
    }

    val hasCandidates: Boolean get() = workers.isNotEmpty() || reviewer != null

    suspend fun generate(stage: ResearchModelStage, prompt: String, cap: Int, extractedBy: String = ""): ResearchModelReply? {
        if (!stillEnabled() || cap <= 0) return null
        val candidates = (if (stage == ResearchModelStage.REVIEW) listOfNotNull(reviewer) + workers else workers).distinctBy { it.profileUid }
        for (target in candidates) {
            if (!stillEnabled()) return null
            val event = ResearchModelEvent(UUID.randomUUID().toString(), stage.name, target.profileUid, target.model, target.local, "QUEUED", wallClock(), inputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(prompt))
            if ((failures[target.profileUid] ?: 0) >= 2) {
                record(event.copy(outcome = "SKIPPED", reason = "MODEL_CIRCUIT_OPEN"))
                continue
            }
            val started = monotonicClock()
            try {
                if (withTimeoutOrNull(minOf(5000L, deadlineMillis)) { target.available() } != true) {
                    record(event.copy(outcome = "SKIPPED", reason = "MODEL_NOT_ADMITTED"))
                    continue
                }
                record(event.copy(outcome = "RUNNING"))
                val reply = withTimeoutOrNull(target.timeoutMillis ?: deadlineMillis) { target.generate(stage, prompt, cap) }
                if (!stillEnabled()) {
                    record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PERMISSION_REVOKED"))
                    return null
                }
                if (reply == null || !valid(stage, parseDelegationObject(reply.text))) {
                    failures[target.profileUid] = (failures[target.profileUid] ?: 0) + 1
                    record(event.copy(outcome = if (reply == null) "TIMED_OUT" else "INVALID_RESPONSE", durationMs = elapsed(started), invocationId = reply?.invocationId, reason = if (reply == null) "MODEL_DEADLINE" else "SCHEMA_VALIDATION_FAILED"))
                    continue
                }
                failures.remove(target.profileUid)
                val independent = stage == ResearchModelStage.REVIEW && extractedBy.isNotBlank() && target.modelKey.isNotBlank() && target.modelKey != extractedBy
                record(event.copy(outcome = "SUCCESS", durationMs = elapsed(started), invocationId = reply.invocationId, outputTokensEstimate = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(reply.text), independent = independent))
                return reply.copy(modelKey = target.modelKey, independent = independent)
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(event.copy(outcome = "CANCELLED", durationMs = elapsed(started), reason = "PARENT_CANCELLED")) }
                throw cancelled
            } catch (_: Exception) {
                failures[target.profileUid] = (failures[target.profileUid] ?: 0) + 1
                record(event.copy(outcome = "FAILED", durationMs = elapsed(started), reason = "MODEL_EXECUTION_FAILED"))
            }
        }
        return null
    }

    private fun elapsed(started: Long): Long = (monotonicClock() - started).coerceAtLeast(0)

    companion object {
        internal fun valid(stage: ResearchModelStage, value: JsonObject?): Boolean = value != null &&
            when (stage) {
                ResearchModelStage.PLAN -> value["questions"] is JsonArray && value["queries"] is JsonArray
                ResearchModelStage.FIND_GAPS -> value["missing"] is JsonArray && value["queries"] is JsonArray
                ResearchModelStage.EXTRACT -> value["claims"] is JsonArray
                ResearchModelStage.REVIEW -> value.text("verdict") in setOf("Supported", "Contradicted", "Insufficient") && value.text("relevance") in setOf("Relevant", "Background", "Irrelevant") && value.text("questionId").isNotBlank()
            }
    }
}
