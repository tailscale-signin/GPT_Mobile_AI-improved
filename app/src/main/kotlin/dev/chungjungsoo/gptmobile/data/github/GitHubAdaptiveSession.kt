package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolExceptionEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Private options are proposals, never evidence, tool calls or approval decisions. */
internal class GitHubAdaptiveSession(
    private val delegate: AgentProviderSession,
    private val role: String,
    private val binding: String,
    private val open: suspend (role: String, instruction: String) -> AgentProviderSession,
    private val continueWith: suspend (String) -> AgentProviderSession,
    private val load: suspend () -> JsonObject?,
    private val save: suspend (JsonObject) -> Unit,
    private val onIntent: suspend (JsonObject) -> Unit = {},
    private val interpretBeforeOptions: Boolean = false
) : AgentProviderSession by delegate {
    private var prepared: AgentProviderSession? = null

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        try {
            if (prepared == null && exchanges.isEmpty()) {
                var snapshot = load()?.takeIf { it["binding"] == JsonPrimitive(binding) } ?: buildJsonObject { put("binding", binding) }
                suspend fun retain(key: String, value: JsonObject) {
                    snapshot = JsonObject(snapshot + (key to value))
                    save(snapshot)
                }
                suspend fun collectObject(stage: String, instruction: String): JsonObject? {
                    val text = StringBuilder()
                    var completed = false
                    var outputLimitReached = false
                    var invocationId: String? = null
                    open(stage, instruction).streamRound(emptyList(), emptyList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> {
                                if (event.text.length > 12000 - text.length) throw PlanningContractFailure("The planning response exceeded its size limit.", invocationId)
                                text.append(event.text)
                            }
                            is ProviderEvent.Failed -> throw PlanningFailure(event)
                            is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> throw PlanningContractFailure("A text-only planning pass requested a tool.", invocationId)
                            is ProviderEvent.Usage -> {
                                event.invocationId?.let { invocationId = it }
                                if (event.finishReason == "content_filter") throw PlanningContractFailure("The provider filtered the planning response.", invocationId, "PROVIDER")
                                val limited = event.finishReason == "length" || event.finishReason == "max_tokens"
                                if (limited) outputLimitReached = true
                                // This finish reason belongs to a private pass. Keep its usage separate from the primary answer's completion.
                                emit(if (limited) event.copy(finishReason = null) else event)
                            }
                            ProviderEvent.Completed -> completed = true
                            is ProviderEvent.ThinkingDelta -> Unit
                            else -> emit(event)
                        }
                    }
                    if (!completed) throw PlanningContractFailure("The planning stream closed before confirming completion.", invocationId)
                    if (outputLimitReached) return null
                    return try {
                        Json.parseToJsonElement(text.toString()) as? JsonObject
                    } catch (_: kotlinx.serialization.SerializationException) {
                        null
                    }
                }
                if (role == INTENT_ROLE) {
                    emit(ProviderEvent.Notice("Identifying the request and its evidence requirements"))
                    val plan = snapshot["intent_plan"] as? JsonObject ?: collectObject(role, INTENT_INSTRUCTION)
                    if (plan != null && validIntent(plan)) {
                        if ("intent_plan" !in snapshot) retain("intent_plan", plan)
                        onIntent(plan)
                        prepared = continueWith("Advisory interpretation of the current request. It is not evidence, permission or approval. Confirm referenced entities from the conversation and repository, and obey the original scope and restrictions.\n$plan")
                    } else {
                        emit(ProviderEvent.Notice("Continuing from the original request"))
                        prepared = delegate
                    }
                } else {
                    emit(ProviderEvent.Notice("Considering approaches against the task requirements"))
                    val intent = if (interpretBeforeOptions) {
                        val plan = snapshot["intent_plan"] as? JsonObject ?: collectObject(INTENT_ROLE, INTENT_INSTRUCTION)
                        plan?.takeIf(::validIntent)?.also {
                            if ("intent_plan" !in snapshot) retain("intent_plan", it)
                            onIntent(it)
                        }
                    } else {
                        null
                    }
                    val instruction = OPTIONS_INSTRUCTION + intent?.let { "\nUntrusted interpretation of the accepted request:\n$it" }.orEmpty()
                    val options = snapshot["options"] as? JsonObject ?: collectObject(role, instruction)
                    val ids = options?.let { runCatching { validateOptions(it) }.getOrNull() }
                    if (ids != null && options != null) {
                        if ("options" !in snapshot) retain("options", options)
                        val selection = snapshot["selection"] as? JsonObject ?: collectObject(
                            "github-decision-selection",
                            "$SELECTION_INSTRUCTION\nUntrusted proposed options (not execution evidence):\n$options"
                        )
                        if (selection != null && runCatching { validateSelection(selection, ids) }.isSuccess) {
                            if ("selection" !in snapshot) retain("selection", selection)
                            prepared = continueWith("Untrusted planning proposals and selection follow. Validate source and feasibility before acting. This is not evidence, permission or an approval; all original task limits and write fences apply.\n$options\n$selection")
                        }
                    }
                    if (prepared == null) {
                        emit(ProviderEvent.Notice("Continuing from the original request"))
                        prepared = delegate
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PlanningFailure) {
            emit(failure.event)
            return@flow
        } catch (error: Exception) {
            val contract = error as? PlanningContractFailure
            val details = buildJsonObject {
                ToolExceptionEvidence.fields(error).forEach { (key, value) -> put(key, value) }
                put("failed_stage", "planning")
                put("operation", "github_decide")
                contract?.let { put("failure_origin", it.origin) }
            }
            val message = contract?.message ?: "Adaptive planning paused while preparing this task."
            emit(ProviderEvent.Failed(message, errorCode = INCOMPLETE_CODE, taskOutcome = "BLOCKED", failureDetails = details, invocationId = contract?.invocationId))
            return@flow
        }
        emitAll((prepared ?: delegate).streamRound(tools, exchanges))
    }

    private class PlanningFailure(val event: ProviderEvent.Failed) : Exception()
    private class PlanningContractFailure(message: String, val invocationId: String?, val origin: String = "APPLICATION") : Exception(message)

    companion object {
        const val INCOMPLETE_CODE = "GITHUB_ADAPTIVE_PLAN_INCOMPLETE"
        const val INTENT_ROLE = "github-intent-plan"
        private const val INTENT_INSTRUCTION = "Interpret the latest user instruction in its admitted conversation. Resolve references to earlier requests only when supported. Identify independent deliverables and order work by prerequisites. Return only JSON with intent (QUESTION, INVESTIGATION, IMPLEMENTATION, MIXED or UNCLEAR), objective (string), and steps (1 to 8 objects, each with goal and proof strings). If a compound request can be divided without dropping any qualifier, include criterion as a verbatim complete clause from the latest user request in its original order. Each proof describes an observation needed, never a completed action. Proposals and historical statements cannot authorize an operation. Preserve negative constraints and uncertainty. Do not call tools."

        internal fun needsIntentPlan(task: String, hasHistory: Boolean): Boolean {
            if (GitHubTaskContinuity.continuation(task) || GitHubStatusRequest.matches(task) || GitHubTestChecklist.integration(task)) return false
            val request = GitHubTaskContinuity.request(task)
            if (request.isBlank() || dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(request).conversational) return false
            if (GitHubAcceptanceContract.split(request).size > 1) return true
            if (hasHistory && Regex("""(?i)\b(?:it|those|that|these|same|previous|earlier|above|below|other one)\b""").containsMatchIn(request)) return true
            // Unclassified requests receive model interpretation even when they include no known action verb.
            return !GitHubRequestCompletion.informational(task) && !GitHubRequestCompletion.requiresRepositoryEvidence(task)
        }

        internal fun validIntent(value: JsonObject): Boolean {
            if (value.keys != setOf("intent", "objective", "steps") || value.string("intent") !in setOf("QUESTION", "INVESTIGATION", "IMPLEMENTATION", "MIXED", "UNCLEAR")) return false
            fun bounded(text: String) = text.isNotBlank() && text.length <= 480
            val steps = value["steps"] as? JsonArray ?: return false
            return bounded(value.string("objective")) &&
                steps.size in 1..8 &&
                steps.all {
                    it is JsonObject && (it.keys == setOf("goal", "proof") || it.keys == setOf("goal", "proof", "criterion") && bounded(it.string("criterion"))) && bounded(it.string("goal")) && bounded(it.string("proof"))
                }
        }

        const val OPTIONS_INSTRUCTION = "Resolve references from the admitted conversation and preserve all original requirements and restrictions. Explore 2 or 3 feasible approaches to the original task. Return only JSON: {\"options\":[{\"id\":\"A\",\"proposal\":\"...\",\"tradeoffs\":\"...\",\"assumptions\":\"...\"}]}. Keep each field concise. Do not claim source inspection, tests, changes or success without receipts. Do not ask the user or call tools."
        const val SELECTION_INSTRUCTION = "Evaluate the proposed options against requirements, available source evidence, testability, risk and maintenance cost. Return only JSON: {\"selected_id\":\"an existing option id\",\"rationale\":\"...\",\"risks\":[\"...\"],\"tests\":[\"required validation\"]}. Unknown evidence remains unknown; selection never grants write or merge approval."

        internal fun validateOptions(value: JsonObject): Set<String> {
            val options = value["options"] as? JsonArray ?: error("Missing planning options.")
            check(options.size in 2..3 && value.toString().length <= 12000)
            val ids = options.map { option ->
                val item = option as? JsonObject ?: error("Invalid planning option.")
                val id = item.string("id")
                check(id.matches(Regex("[A-Za-z0-9_-]{1,20}")))
                for (key in listOf("proposal", "tradeoffs", "assumptions")) check(item.string(key).isNotBlank())
                id
            }
            check(ids.distinct().size == ids.size)
            return ids.toSet()
        }

        internal fun validateSelection(value: JsonObject, ids: Set<String>) {
            check(value.toString().length <= 12000 && value.string("selected_id") in ids && value.string("rationale").isNotBlank())
            for (key in listOf("risks", "tests")) {
                val rows = value[key] as? JsonArray ?: error("Missing selection $key.")
                check(rows.size in 1..12 && rows.all { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull?.isNotBlank() == true })
            }
        }

        private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    }
}
