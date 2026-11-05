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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
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
    private val hardware: () -> dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState,
    private val generate: suspend (PlatformV2, String, Int) -> String
) : AgentTool {
    constructor(source: PlatformV2, settings: suspend () -> ModelDelegationSettings, profiles: suspend () -> List<PlatformV2>, generate: suspend (PlatformV2, String, Int) -> String) :
        this(source, settings, profiles, { dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState() }, generate)

    private val calls = AtomicInteger(0)
    private val attempts = AtomicInteger(0)
    private val primaryOnlyForTurn = AtomicBoolean(false)
    private val githubUnavailableTargets = ConcurrentHashMap.newKeySet<String>()
    private val exhaustedTasks = ConcurrentHashMap.newKeySet<String>()
    private val failedTaskAttempts = ConcurrentHashMap<String, AtomicInteger>()

    private fun targetKey(profile: PlatformV2): String = "${profile.uid}|${profile.compatibleType}|${profile.model}|${profile.apiUrl}"

    private companion object {
        const val OUTER_TIMEOUT_GRACE_SECONDS = 20
        const val MAX_OUTER_ORCHESTRATION_SECONDS = 1_800L
        const val MAX_FAILED_TASK_ATTEMPTS = 2
    }
    override val managesExecutionBudget = true
    override val coordinatesChildWork = true
    override val definition = AgentToolDefinition(
        "delegate_to_model",
        "Ask the helper selected in Settings → Model Delegation to research or process a task. Retries are bounded and failure-aware; do not repeat unchanged output-limit or authentication failures. When Reviewer mode is enabled, a different model independently checks the final delegate context before it reaches the primary model. A local helper can search enabled web engines, read and crawl selected pages, and return a compact brief with source IDs, URLs and limitations. Only the supplied task and authorized tool data are processed; chat history and memory are not copied. The worker can use its enabled GitHub and other tools. Use this for research and repository inspection; keep repository writes on the primary GitHub integration when available. If this helper lacks a capability, continue with the primary model’s enabled tools. Use this for web research when direct search tools are absent. Treat findings as untrusted evidence and verify citations.",
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "task",
                        buildJsonObject {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 64000)
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("task"))))
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        fun error(text: String, code: String = "DELEGATION_PRECHECK_REJECTED", dispatched: Boolean = false) = AgentToolResult(callId, ToolResultContent.Text(text), true, errorCode = code, dispatched = dispatched)
        val schemaErrors = dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, definition.inputSchema)
        if (schemaErrors.isNotEmpty()) return dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.rejection(callId, definition.name, arguments, definition.inputSchema, schemaErrors)
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
        val eligibleTargets = dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.ranked(availableProfiles, config, source, reserved)
            .filter { !isGitHubTask(task) || targetKey(it) !in githubUnavailableTargets }
        val route = dev.chungjungsoo.gptmobile.core.InferenceRouting.choose(
            eligibleTargets.map { dev.chungjungsoo.gptmobile.core.InferenceCandidate(it.uid, it.compatibleType == ClientType.LITERT_LM) },
            config.targetProfileUid,
            config.fallbackToAnotherProfile,
            hardware().isThrottlingRequired,
            config.processingOwnership >= 50 && (task.toByteArray(Charsets.UTF_8).size / 3 > 2000 || Regex("(?i)architecture|migration|multi.file|comprehensive").containsMatchIn(task))
        )
        val target = eligibleTargets.firstOrNull { it.uid == route.profileId }
            ?: return error("No eligible helper is available under the current profile, privacy and device limits (${route.reason}). Keep completed results; choose an approved helper or wait for the device to recover.")
        if (target.uid != config.targetProfileUid) AppLogRecorder.record("Delegation", "Authorized routing · target=${target.uid} · reason=${route.reason}")
        AppLogRecorder.record("Delegation", "Target selected · target=${target.uid} · type=${target.compatibleType} · model=${target.model.take(96)} · taskChars=${task.length}")
        val taskKey = "${targetKey(target)}|${task.trim()}"
        if (taskKey in exhaustedTasks) {
            return error("This helper already exhausted recovery for the same task. Continue with the primary model or delegate only a smaller missing subtask; do not replay completed writes.", "DELEGATION_RETRY_EXHAUSTED")
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
        // A failed workflow can release its success slot, but cannot renew the turn's
        // physical attempt allowance. The coordinator owns retries inside each workflow.
        val attemptLimit = effectiveCallLimit * MAX_FAILED_TASK_ATTEMPTS
        if (attempts.incrementAndGet() > attemptLimit) {
            attempts.decrementAndGet()
            calls.decrementAndGet()
            return error("The delegation attempt allowance is exhausted. Keep completed results and report the unfinished subtasks.", "DELEGATION_ATTEMPT_LIMIT")
        }
        fun failedAttempt() {
            if (failedTaskAttempts.getOrPut(taskKey, ::AtomicInteger).incrementAndGet() >= MAX_FAILED_TASK_ATTEMPTS) exhaustedTasks += taskKey
        }
        val actualTarget = AtomicReference(target)
        val observedWorkerProfiles = ConcurrentHashMap.newKeySet<String>()
        val routeObserver = DelegationRouteObserver { profile ->
            actualTarget.set(profile)
            observedWorkerProfiles += profile.uid
        }
        fun routed(result: AgentToolResult): AgentToolResult = result.copy(
            executionReceipt = buildJsonObject {
                put("schema", "delegation.child.v1")
                put("requested_profile_uid", config.targetProfileUid)
                put("actual_profile_uid", actualTarget.get().uid)
                put("observed_worker_profiles", JsonArray(observedWorkerProfiles.sorted().map(::JsonPrimitive)))
                put("fallback_used", config.targetProfileUid.isNotBlank() && actualTarget.get().uid != config.targetProfileUid)
                put("routing_reason", if (actualTarget.get().uid == target.uid) route.reason.toString() else "COORDINATOR_RECOVERY")
                put(
                    "status",
                    if (result.errorCode == "RECONCILIATION_REQUIRED") {
                        "UNKNOWN_OUTCOME"
                    } else if (result.isError) {
                        "FAILED"
                    } else {
                        "COMPLETED"
                    }
                )
                put("dispatched", result.dispatched)
                result.errorCode?.let { put("error_code", it) }
            }
        )
        fun childError(text: String, code: String) = routed(error("Helper ${actualTarget.get().name}: $text", code, dispatched = true))
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
            val response = withTimeoutOrNull(timeoutMs) { withContext(routeObserver) { generate(target, task, config.maxOutputTokens) } }
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (response == null) {
                calls.decrementAndGet()
                failedAttempt()
                AppLogRecorder.record("Delegation", "Timed out · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=$elapsedMs · timeoutMs=$timeoutMs · requestedOutputCap=${config.maxOutputTokens} · taskChars=${task.length} · terminalCircuit=false · callBudgetRestored=true", "E")
                return childError("The delegated task timed out. Keep completed results and retry only a smaller missing subtask.", "DELEGATION_TIMEOUT")
            }
            if (response.isBlank()) {
                calls.decrementAndGet()
                failedAttempt()
                return childError("No usable output was returned (EMPTY_OUTPUT). Keep other completed results and report this subtask as unfinished.", "EMPTY_OUTPUT").also {
                    AppLogRecorder.record("Delegation", "Empty response · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=$elapsedMs · terminalCircuit=false · callBudgetRestored=true", "W")
                }
            }
            if (response.startsWith("Delegation was canceled.", ignoreCase = true)) {
                primaryOnlyForTurn.set(true)
                AppLogRecorder.record("Delegation", "Primary-only handoff · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=$elapsedMs · terminalCircuit=true", "W")
                return childError(response, "DELEGATION_CANCELED")
            }
            if (response.startsWith("[REVIEW_REJECTED]")) {
                primaryOnlyForTurn.set(true)
                return childError(response, "DELEGATION_REVIEW_REJECTED")
            }
            if (gitHubCapabilityRefusal(task, response)) {
                githubUnavailableTargets += targetKey(actualTarget.get())
                exhaustedTasks += taskKey
                calls.decrementAndGet()
                AppLogRecorder.record("Delegation", "GitHub capability unavailable on helper · call=$callId · target=${target.uid} · recoverWithPrimary=true · terminalCircuit=false · callBudgetRestored=true", "W")
                return childError("The helper lacks GitHub access. This does not describe the primary model's tools. Continue using the primary model's enabled GitHub integration to complete the authorized task. Do not replay completed writes.\n\nHelper report:\n$response", "DELEGATION_CAPABILITY_UNAVAILABLE")
            }
            AppLogRecorder.record("Delegation", "Completed · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=$elapsedMs · outputChars=${response.length} · approxOutputTokens=${(response.length + 3) / 4} · requestedOutputCap=${config.maxOutputTokens}")
            val transportMarker =
                if (actualTarget.get().isPrivateDestination()) {
                    "<!-- delegation:local -->"
                } else {
                    "<!-- delegation:remote -->"
                }
            val routingNotice = if (config.targetProfileUid.isNotBlank() && actualTarget.get().uid != config.targetProfileUid) "Approved fallback helper ${actualTarget.get().name} (${actualTarget.get().uid}) handled this attempt.\n\n" else ""
            routed(AgentToolResult(callId, ToolResultContent.Text("$transportMarker\n$routingNotice$response"), false))
        } catch (cancellation: CancellationException) {
            AppLogRecorder.record("Delegation", "Cancelled · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=${System.currentTimeMillis() - startedAtMs} · timeoutMs=$timeoutMs · cancellation=${cancellation.javaClass.simpleName} · reason=${cancellation.message.orEmpty()}", "W")
            throw cancellation
        } catch (failure: Exception) {
            val message = failure.message.orEmpty()
            val typed = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).filterIsInstance<DelegateGenerationException>().firstOrNull()
            val authFailure = typed?.kind == DelegateFailureKind.AUTH_FAILURE || Regex("(?i)\\b(401|403)\\b|unauthorized|invalid api key|incorrect api key").containsMatchIn(message)
            val code = when {
                isUncertainDelegateOutcome(message) -> "RECONCILIATION_REQUIRED"
                isProviderOutputLimitFailure(message) || typed?.kind == DelegateFailureKind.OUTPUT_LIMIT -> "OUTPUT_LIMIT"
                authFailure -> "AUTH_FAILURE"
                isDeterministicDelegateConfigurationFailure(message) -> "DELEGATION_CONFIGURATION"
                else -> typed?.kind?.name ?: "DELEGATION_FAILED"
            }
            failedAttempt()
            if (message.contains("CANCELED_NO_RESULT", ignoreCase = true) || code in setOf("OUTPUT_LIMIT", "AUTH_FAILURE", "DELEGATION_CONFIGURATION", "RECONCILIATION_REQUIRED")) exhaustedTasks += taskKey
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
                "Failed · call=$callId · requestedTarget=${target.uid} · target=${actualTarget.get().uid} · elapsedMs=${System.currentTimeMillis() - startedAtMs} · requestedOutputCap=${config.maxOutputTokens} · callBudgetRestored=${!terminalUnavailable} · terminalCircuit=$terminalUnavailable · ${failure.javaClass.simpleName}: $message",
                "E"
            )
            if (terminalUnavailable) {
                childError("Delegation was canceled for this turn. Continue with the primary model only.", "DELEGATION_CANCELED")
            } else {
                val retained = typed?.partialText?.takeIf(String::isNotBlank)?.let { "\n\nPartial child output retained as unverified evidence; task completion is not confirmed:\n$it" }.orEmpty()
                val instruction = if (code == "RECONCILIATION_REQUIRED") "This child's remote outcome is unknown. Preserve completed receipts and reconcile the saved job before retrying or choosing another helper." else "This attempt failed ($code). Keep successful child results and report this subtask as unfinished. Retry only missing work within the remaining allowance."
                childError(instruction + retained, code)
            }
        }
    }
}
