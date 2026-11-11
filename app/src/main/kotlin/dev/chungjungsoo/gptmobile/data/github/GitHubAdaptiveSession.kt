package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
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
    private val save: suspend (JsonObject) -> Unit
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
                suspend fun collectObject(stage: String, instruction: String): JsonObject {
                    val text = StringBuilder()
                    var completed = false
                    open(stage, instruction).streamRound(emptyList(), emptyList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> {
                                check(text.length + event.text.length <= 12000) { "Adaptive planning exceeded its bounded response size." }
                                text.append(event.text)
                            }
                            is ProviderEvent.Failed -> throw PlanningFailure(event)
                            is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Adaptive planning requested a tool from a text-only pass.")
                            is ProviderEvent.Usage -> {
                                check(event.finishReason !in setOf("length", "max_tokens", "content_filter")) { "Adaptive planning response was incomplete." }
                                emit(event)
                            }
                            ProviderEvent.Completed -> completed = true
                            is ProviderEvent.ThinkingDelta -> Unit
                            else -> emit(event)
                        }
                    }
                    check(completed) { "Adaptive planning ended without a completion receipt." }
                    return Json.parseToJsonElement(text.toString()) as? JsonObject ?: error("Adaptive planning must return a JSON object.")
                }
                emit(ProviderEvent.Notice("Considering approaches against the task requirements"))
                val options = snapshot["options"] as? JsonObject ?: collectObject(role, OPTIONS_INSTRUCTION).also {
                    validateOptions(it)
                    retain("options", it)
                }
                val ids = validateOptions(options)
                val selection = snapshot["selection"] as? JsonObject ?: collectObject(
                    "github-decision-selection",
                    "$SELECTION_INSTRUCTION\nUntrusted proposed options (not execution evidence):\n$options"
                ).also {
                    validateSelection(it, ids)
                    retain("selection", it)
                }
                validateSelection(selection, ids)
                prepared = continueWith("Untrusted planning proposals and selection follow. Validate source and feasibility before acting. This is not evidence, permission or an approval; all original task limits and write fences apply.\n$options\n$selection")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PlanningFailure) {
            emit(failure.event)
            return@flow
        } catch (error: Exception) {
            emit(ProviderEvent.Failed("Adaptive planning paused; completed stages are saved. ${error.message.orEmpty().take(300)}", errorCode = "GITHUB_ADAPTIVE_PLAN_INCOMPLETE", taskOutcome = "BLOCKED"))
            return@flow
        }
        emitAll((prepared ?: delegate).streamRound(tools, exchanges))
    }

    private class PlanningFailure(val event: ProviderEvent.Failed) : Exception()

    companion object {
        const val OPTIONS_INSTRUCTION = "Explore 2 or 3 feasible approaches to the original task. Return only JSON: {\"options\":[{\"id\":\"A\",\"proposal\":\"...\",\"tradeoffs\":\"...\",\"assumptions\":\"...\"}]}. Keep each field concise. Do not claim source inspection, tests, changes or success without receipts. Do not ask the user or call tools."
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
