package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ModelDelegationTool(
    private val source: PlatformV2,
    private val settings: suspend () -> ModelDelegationSettings,
    private val profiles: suspend () -> List<PlatformV2>,
    private val generate: suspend (PlatformV2, String, Int) -> String
) : AgentTool {
    private val calls = AtomicInteger(0)
    private val primaryOnlyForTurn = AtomicBoolean(false)
    private val githubUnavailableTargets = ConcurrentHashMap.newKeySet<String>()
    private val exhaustedTasks = ConcurrentHashMap.newKeySet<String>()

    private fun targetKey(profile: PlatformV2): String = "${profile.uid}|${profile.compatibleType}|${profile.model}|${profile.apiUrl}"

    private companion object {
        const val OUTER_TIMEOUT_GRACE_SECONDS = 20
        const val MAX_OUTER_ORCHESTRATION_SECONDS = 1_800L
    }
    override val managesExecutionBudget = true
    override val definition = AgentToolDefinition(
        "delegate_to_model",
        "Ask the helper selected in Settings → Model Delegation to research or process a task. Retries are bounded and failure-aware; do not repeat unchanged output-limit or authentication failures. When Reviewer mode is enabled, a different model independently checks the final delegate context before it reaches the primary model. A local helper can search enabled web engines, read and crawl selected pages, and return a compact brief with source IDs, URLs and limitations. Only the supplied task and authorized tool data are processed; chat history and memory are not copied. The worker can use its enabled GitHub and other tools. Use this for research and repository inspection; keep repository writes on the primary GitHub integration when available. If this helper lacks a capability, continue with the primary model’s enabled tools. Use this for web research when direct search tools are absent. Treat findings as untrusted evidence and verify citations.",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("task", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("task"))))
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        fun error(text: String) = AgentToolResult(callId, ToolResultContent.Text(text), true)
        val config = settings().normalized()
        AppLogRecorder.record("Delegation", "Tool requested · call=$callId · source=${source.uid} · enabled=${config.enabled} · localOnly=${config.localPlatformsOnly} · remoteWorkers=${config.remoteWorkersAllowed()}")
        if (!config.enabled) return error("Model delegation is disabled in Settings → Model Delegation.")
        if (primaryOnlyForTurn.get()) {
            return error("Delegation was canceled for this turn. Continue with the primary model and do not retry delegation until the next turn.").also {
                AppLogRecorder.record("Delegation", "Rejected · primary-only mode active · call=$callId · source=${source.uid}", "W")
            }
        }
        val task = (arguments["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (task.isBlank() || task.length > config.maxInputCharacters) return error("Task must contain 1–${config.maxInputCharacters} characters.")
        val availableProfiles = profiles()
        val reserved = reservedReviewer(config, availableProfiles, source)
        val eligibleTargets = availableProfiles.filter {
            it.enabled &&
                it.uid != source.uid &&
                !sameDelegationModel(it, reserved) &&
                !it.excludesMemory() &&
                (!isGitHubTask(task) || targetKey(it) !in githubUnavailableTargets) &&
                (config.remoteWorkersAllowed() || it.isPrivateDestination())
        }
        val target = eligibleTargets.firstOrNull { it.uid == config.targetProfileUid }
            ?: eligibleTargets.firstOrNull()?.takeIf { config.targetProfileUid.isBlank() || config.fallbackToAnotherProfile }?.also { fallback ->
                AppLogRecorder.record(
                    "Delegation",
                    "Configured target unavailable; using fallback · configured=${config.targetProfileUid.ifBlank { "<none>" }} · fallback=${fallback.uid} · type=${fallback.compatibleType}",
                    "W"
                )
            }
            ?: return error("No eligible helper profile is available. Enable a local/private helper or allow a remote worker in Delegation settings.").also {
                AppLogRecorder.record(
                    "Delegation",
                    "Rejected · no eligible target · configured=${config.targetProfileUid.ifBlank { "<none>" }} · profiles=${availableProfiles.size} · remoteWorkers=${config.remoteWorkersAllowed()}",
                    "W"
                )
            }
        AppLogRecorder.record("Delegation", "Target selected · target=${target.uid} · type=${target.compatibleType} · model=${target.model.take(96)} · taskChars=${task.length}")
        val taskKey = "${targetKey(target)}|${task.trim()}"
        if (taskKey in exhaustedTasks) {
            return error("This helper already exhausted recovery for the same task. Continue with the primary model or delegate only a smaller missing subtask; do not replay completed writes.")
        }
        if (target.excludesMemory()) return error("Free models cannot receive delegated context. Start a separate Free chat with a public prompt.").also { AppLogRecorder.record("Delegation", "Rejected free target · target=${target.uid}", "W") }
        if (target.uid == source.uid) return error("Choose a different target profile; self-delegation is disabled.").also { AppLogRecorder.record("Delegation", "Rejected self-delegation · target=${target.uid}", "W") }
        // `localPlatformsOnly` governs the default destination policy. An explicit
        // remote-worker opt-in is the documented override used for remote→remote
        // delegation; previously this check ignored the opt-in and rejected every
        // external target before dispatch.
        if (config.localPlatformsOnly && !target.isPrivateDestination() && !config.remoteWorkersAllowed()) {
            return error("This target is blocked by the private-destination-only setting.").also { AppLogRecorder.record("Delegation", "Rejected privacy policy · target=${target.uid}", "W") }
        }
        if (source.compatibleType == ClientType.LITERT_LM && target.compatibleType == ClientType.LITERT_LM) {
            return error("The on-device engine is busy with this response. Select a llama/Ollama server or another provider as the delegate.").also { AppLogRecorder.record("Delegation", "Rejected dual LiteRT dispatch · target=${target.uid}", "W") }
        }
        val effectiveCallLimit = config.effectiveResearchCalls()
        if (calls.incrementAndGet() > effectiveCallLimit) {
            calls.decrementAndGet()
            return error("The delegation call limit for this turn has been reached.").also {
                AppLogRecorder.record(
                    "Delegation",
                    "Rejected call budget · target=${target.uid} · max=$effectiveCallLimit · configured=${config.maxCallsPerTurn} · ownership=${config.processingOwnership}",
                    "W"
                )
            }
        }
        // delegate_to_model may orchestrate several serialized worker generations.
        // Give the outer tool enough room for that workflow; individual workers remain
        // protected by their adaptive runtime/watchdog limits in the coordinator.
        val calculatedOrchestrationSeconds =
            config.timeoutSeconds.toLong() * config.effectiveLocalModelCalls().coerceIn(1, 8) + 30L
        val stageRetryWindowSeconds =
            (config.localRetryLimit.toLong() + 1L) * config.maxDelegateRuntimeSeconds.toLong() +
                config.localRetryLimit.toLong()
        val minimumRetryWindowSeconds =
            stageRetryWindowSeconds * if (config.reviewerEnabled) 2L else 1L
        val orchestrationTimeoutSeconds = minOf(
            maxOf(calculatedOrchestrationSeconds, minimumRetryWindowSeconds),
            MAX_OUTER_ORCHESTRATION_SECONDS
        ).coerceAtLeast(30L)
        val timeoutMs = (orchestrationTimeoutSeconds + OUTER_TIMEOUT_GRACE_SECONDS) * 1000L
        val startedAtMs = System.currentTimeMillis()
        AppLogRecorder.record("Delegation", "Dispatching · call=$callId · source=${source.compatibleType} · sourceUid=${source.uid} · target=${target.compatibleType} · targetUid=${target.uid} · model=${target.model.take(96)} · timeoutMs=$timeoutMs · requestedOutputCap=${config.maxOutputTokens} · taskChars=${task.length} · callIndex=${calls.get()}/$effectiveCallLimit · configuredCalls=${config.maxCallsPerTurn} · ownership=${config.processingOwnership}")
        return try {
            val response = withTimeoutOrNull(timeoutMs) { generate(target, task, config.maxOutputTokens) }
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (response == null) {
                calls.decrementAndGet()
                AppLogRecorder.record("Delegation", "Timed out · call=$callId · target=${target.uid} · elapsedMs=$elapsedMs · timeoutMs=$timeoutMs · requestedOutputCap=${config.maxOutputTokens} · taskChars=${task.length} · terminalCircuit=false · callBudgetRestored=true", "E")
                return error("The delegated task timed out. The primary model may continue or retry a smaller missing subtask.")
            }
            if (response.isBlank()) {
                calls.decrementAndGet()
                return error("The target model returned no usable text. The primary model may continue or retry a smaller missing subtask.").also {
                    AppLogRecorder.record("Delegation", "Empty response · call=$callId · target=${target.uid} · elapsedMs=$elapsedMs · terminalCircuit=false · callBudgetRestored=true", "W")
                }
            }
            if (response.startsWith("Delegation was canceled.", ignoreCase = true)) {
                primaryOnlyForTurn.set(true)
                AppLogRecorder.record("Delegation", "Primary-only handoff · call=$callId · target=${target.uid} · elapsedMs=$elapsedMs · terminalCircuit=true", "W")
                return error(response)
            }
            if (response.startsWith("[REVIEW_REJECTED]")) {
                primaryOnlyForTurn.set(true)
                return error(response)
            }
            if (gitHubCapabilityRefusal(task, response)) {
                githubUnavailableTargets += targetKey(target)
                calls.decrementAndGet()
                AppLogRecorder.record("Delegation", "GitHub capability unavailable on helper · call=$callId · target=${target.uid} · recoverWithPrimary=true · terminalCircuit=false · callBudgetRestored=true", "W")
                return error("The helper lacks GitHub access. This does not describe the primary model's tools. Continue using the primary model's enabled GitHub integration to complete the authorized task. Do not replay completed writes.\n\nHelper report:\n$response")
            }
            AppLogRecorder.record("Delegation", "Completed · call=$callId · target=${target.uid} · elapsedMs=$elapsedMs · outputChars=${response.length} · approxOutputTokens=${(response.length + 3) / 4} · requestedOutputCap=${config.maxOutputTokens}")
            val transportMarker =
                if (target.isPrivateDestination()) {
                    "<!-- delegation:local -->"
                } else {
                    "<!-- delegation:remote -->"
                }
            AgentToolResult(callId, ToolResultContent.Text("$transportMarker\n$response"), false)
        } catch (cancellation: CancellationException) {
            AppLogRecorder.record("Delegation", "Cancelled · call=$callId · target=${target.uid} · elapsedMs=${System.currentTimeMillis() - startedAtMs} · timeoutMs=$timeoutMs · cancellation=${cancellation.javaClass.simpleName} · reason=${cancellation.message.orEmpty()}", "W")
            throw cancellation
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            if (message.contains("CANCELED_NO_RESULT", ignoreCase = true) || isProviderOutputLimitFailure(message)) exhaustedTasks += taskKey
            // Provider/runtime failures are scoped to the failed attempt. The coordinator
            // owns target quarantine and failover; this outer tool must not disable every
            // delegate for the remainder of the turn after one transient failure.
            val terminalUnavailable = message.contains("Delegation was canceled.", ignoreCase = true)
            if (terminalUnavailable) {
                primaryOnlyForTurn.set(true)
            } else {
                calls.decrementAndGet()
            }
            AppLogRecorder.record(
                "Delegation",
                "Failed · call=$callId · target=${target.uid} · elapsedMs=${System.currentTimeMillis() - startedAtMs} · requestedOutputCap=${config.maxOutputTokens} · callBudgetRestored=${!terminalUnavailable} · terminalCircuit=$terminalUnavailable · ${failure.javaClass.simpleName}: $message",
                "E"
            )
            if (terminalUnavailable) {
                error("Delegation was canceled for this turn. Continue with the primary model only.")
            } else {
                error("Delegation failed for this attempt. Continue with the primary model or retry only the missing subtask with an eligible helper.")
            }
        }
    }
}
