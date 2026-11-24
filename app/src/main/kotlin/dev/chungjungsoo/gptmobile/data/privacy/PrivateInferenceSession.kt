package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ProviderUsagePhase
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class PrivateInferenceSession private constructor(
    private val verifier: PrivacyProviderVerifier,
    private val store: PrivacyModeStore,
    private val profile: PlatformV2,
    private val turns: List<ConversationTurn>,
    private val constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints,
    private val alternatives: suspend () -> String,
    private val synthetic: PrivateSyntheticInput?
) : AgentProviderSession {
    constructor(
        verifier: PrivacyProviderVerifier,
        store: PrivacyModeStore,
        profile: PlatformV2,
        turns: List<ConversationTurn>,
        constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints,
        alternatives: suspend () -> String
    ) : this(verifier, store, profile, turns, constraints, alternatives, null)

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
        val inferenceDispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val protection = currentCoroutineContext()[PrivateProtectionContext]
        var proof: PrivateDispatchContext? = null
        var protocol: PrivateMarkerProtocol? = null
        var answerRestorer: PrivateStreamingRestorer? = null
        var reasoningRestorer: PrivateStreamingRestorer? = null
        var timing: PrivateStreamTiming? = null
        var responseId: Long? = null
        var completed = false
        var deliveryMode = PrivateDeliveryMode.INCREMENTAL
        fun recordStream() {
            val answer = answerRestorer
            val reasoning = reasoningRestorer
            val markerProtocol = protocol
            if (answer != null && reasoning != null && markerProtocol != null) {
                timing?.let { proof?.stream(it.observation(answer, reasoning, markerProtocol, completed, deliveryMode)) }
            }
        }
        try {
            check(tools.isEmpty() && exchanges.isEmpty()) { "External tools require separate privacy verification." }
            check(PrivacyModeStore.active()) { "This request has no private conversation policy." }
            val state = store.effectiveSnapshot(true)
            val maskingStarted = synthetic?.let { System.nanoTime() }
            val mask = PrivateTextMask(state.categories, synthetic?.privateTerms(profile, turns) ?: store.privateTerms())
            val dispatch = PrivateDispatchContext.Builder(state.revision, state.categories)
            mask.seedIdentities(listOfNotNull(profile.systemPrompt) + turns.flatMap { listOfNotNull(it.userMessage.content, it.assistantMessage?.content) })
            val maskedSystem = profile.systemPrompt?.let { original ->
                mask.protectWithReceipt(original).also { dispatch.record(PrivateDispatchSurfaceKind.SYSTEM_PROMPT, original, it) }.text
            }
            var currentRequest = ""
            val history = buildJsonArray {
                fun message(role: String, text: String, source: dev.chungjungsoo.gptmobile.data.database.entity.MessageV2, current: Boolean = false) {
                    if (text.isNotBlank()) {
                        val protected = mask.protectWithReceipt(text)
                        dispatch.record(if (role == "assistant") PrivateDispatchSurfaceKind.ASSISTANT_HISTORY else PrivateDispatchSurfaceKind.USER_MESSAGE, text, protected)
                        protection?.input(source, protected.receipt)
                        if (current) currentRequest = protected.text
                        add(
                            buildJsonObject {
                                put("role", role)
                                put("content", protected.text)
                            }
                        )
                    }
                }
                turns.forEach { turn ->
                    check(turn.userMessage.attachments.isEmpty() && turn.assistantMessage?.attachments.isNullOrEmpty()) { "Attachments require separate privacy verification." }
                    message("user", turn.userMessage.content, turn.userMessage, turn.isCurrentTurn)
                    turn.assistantMessage?.let { message("assistant", it.content, it) }
                }
            }
            val issuedMarkers = mask.issuedMarkers()
            val markerProtocol = PrivateMarkerProtocol(issuedMarkers, currentRequest).also { protocol = it }
            val messages = buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", dispatch.systemPrompt(markerProtocol, maskedSystem))
                    }
                )
                history.forEach(::add)
            }
            val preferred = constraints.outputLimit(profile.maxTokens) ?: 4096
            val limit = if (profile.compatibleType == ClientType.FREE) dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.requireFor(profile).outputAllowance(preferred) else preferred
            // The response map exists only for this invocation. No plaintext mapping is persisted.
            val dispatchProof = dispatch.seal(mask.summary(), issuedMarkers).also { proof = it }
            val restore = mask.sealWithReceipts()
            if (synthetic != null && maskingStarted != null) synthetic.prepared(dispatchProof, System.nanoTime() - maskingStarted)
            val originalContext = currentCoroutineContext()
            val authorization = originalContext[RequestAuthorizationGuard]
            val privateAuthority = RequestAuthorizationGuard("${authorization?.revision.orEmpty()}:privacy-${state.revision}", authorization?.authority) {
                check(store.effectiveSnapshot(true) == state) { "Privacy settings changed. Resend with the current settings." }
                authorization?.validate?.invoke()
                check(store.effectiveSnapshot(true) == state) { "Privacy settings changed. Resend with the current settings." }
            }
            privateAuthority.validate()
            val answer = PrivateStreamingRestorer(markerProtocol, restore).also { answerRestorer = it }
            val reasoning = PrivateStreamingRestorer(markerProtocol, restore, assessAnswer = false).also { reasoningRestorer = it }
            val observed = PrivateStreamTiming().also { timing = it }
            responseId = protection?.beginResponse()
            val reply = originalContext[PrivateToolReplyMask]
            val control = if (reply != null) StringBuilder() else null
            suspend fun publish(result: PrivateProtectedText) {
                if (result.text.isEmpty()) return
                currentCoroutineContext().ensureActive()
                observed.restoredAnswer()
                responseId?.let { protection?.restored(it, result) }
                recordStream()
                emit(ProviderEvent.TextDelta(result.text))
            }
            val upstream = flow<PrivateProviderFrame> {
                suspend fun transmit() {
                    // Verify after acquiring the free-provider permit. Queued work cannot reuse
                    // the eligibility decision made when the conversation was opened.
                    val verified = verifier.verify(profile)
                    val route = verified.route ?: throw RouteUnavailable(verified.decision)
                    val requestModel = route.model ?: profile.model
                    val body = wireBody(route, requestModel, messages, limit)
                    val config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig(
                        apiUrl = route.url, token = null, profileUid = profile.uid, providerType = profile.compatibleType.name,
                        lockedApiUrl = route.url, lockedModel = requestModel, contextCeiling = constraints.contextCeiling,
                        requireKnownCapacity = constraints.requireKnownCapacity, permissionRevision = privateAuthority.revision,
                        requestRole = constraints.requestRole ?: "primary"
                    )
                    val wire = verifier.preparation.prepareQueued(body.toString(), config, requestModel, limit)
                    protection?.flushInputs()
                    val request = Request.Builder().url(route.url).apply {
                        if (route.kind == ClientType.ANTHROPIC) {
                            header("x-api-key", profile.token.orEmpty())
                            header("anthropic-version", "2023-06-01")
                        } else {
                            profile.token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
                        }
                        header("Cache-Control", "no-store")
                        if (route.kind == ClientType.OPENROUTER) header("X-OpenRouter-Cache", "false")
                    }.post(wire.toRequestBody("application/json".toMediaType())).build()
                    // Recheck on the network thread just before the body can be written.
                    val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
                    val dispatchContext = currentCoroutineContext()
                    val client = verifier.http.newBuilder().addNetworkInterceptor { chain ->
                        val proof = route.approval
                        if (!attempted.compareAndSet(false, true)) {
                            throw PrivateDispatchPolicyChanged()
                        }
                        try {
                            recheckDispatch(dispatchContext) {
                                check(verifier.routingUnchanged(profile, route))
                                check(verifier.accountUnchanged(profile, route))
                                privateAuthority.validate()
                                check(store.effectiveSnapshot(true) == state)
                            }
                            check(proof == null || verifier.approval(profile, route.url, proof.policyUrl) == proof)
                        } catch (_: Exception) {
                            throw PrivateDispatchPolicyChanged()
                        }
                        inferenceDispatched.set(true)
                        chain.proceed(chain.request())
                    }.build()
                    PrivateProviderStream.read(client, request, route.kind).collect { emit(it) }
                }
                if (profile.compatibleType == ClientType.FREE) {
                    val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.requireFor(profile)
                    check(dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile) == null) { "Free provider unavailable." }
                    dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter.shared.withRequest(provider, profile.model, "anonymous") { transmit() }
                } else {
                    transmit()
                }
            }
            upstream.flowOn(privateAuthority + dispatchProof).buffer(1).collect { frame ->
                currentCoroutineContext().ensureActive()
                try {
                    privateAuthority.validate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw PrivateDispatchPolicyChanged()
                }
                when (frame) {
                    is PrivateProviderFrame.Delivery -> {
                        deliveryMode = if (control != null) PrivateDeliveryMode.TOOL_CONTROL_BUFFERED else frame.mode
                        recordStream()
                    }
                    is PrivateProviderFrame.Answer -> {
                        observed.answer()
                        if (control != null) {
                            if (frame.text.length > 65_536 - control.length) throw PrivateProviderResponseException(PrivateResponseFailure.RESPONSE_LIMIT)
                            control.append(frame.text)
                        } else {
                            publish(answer.accept(frame.text))
                        }
                    }
                    is PrivateProviderFrame.Reasoning -> {
                        observed.reasoning()
                        val restored = reasoning.accept(frame.text)
                        if (restored.text.isNotEmpty()) emit(ProviderEvent.ThinkingDelta(restored.text))
                    }
                    is PrivateProviderFrame.Usage -> emit(
                        ProviderEvent.Usage(
                            inputTokens = frame.input,
                            outputTokens = frame.output,
                            totalTokens = frame.total,
                            cumulative = true,
                            finishReason = frame.finishReason,
                            phase = if (frame.final) ProviderUsagePhase.FINAL else ProviderUsagePhase.INTERIM
                        )
                    )
                    PrivateProviderFrame.Completed -> {
                        val reasoningTail = reasoning.finish()
                        if (reasoningTail.text.isNotEmpty()) emit(ProviderEvent.ThinkingDelta(reasoningTail.text))
                        if (control != null) {
                            val prepared = markerProtocol.prepare(control.toString())
                            val restoreStarted = System.nanoTime()
                            val restored = requireNotNull(reply).restore(prepared, restore)
                            answer.recordCompleteRestoration(restored, System.nanoTime() - restoreStarted)
                            control.clear()
                            publish(restored)
                        }
                        publish(answer.finish())
                    }
                }
            }
            if (markerProtocol.anomalyCodes().any { it == PrivateOutputAnomaly.POSSIBLE_PROTECTED_FIELD_OMISSION || it == PrivateOutputAnomaly.POSSIBLE_VALUE_REENTRY_REQUEST }) {
                emit(ProviderEvent.Notice("This draft may contain an unfilled protected field or request a value already supplied. Review it before using it; task completeness has not been verified."))
            }
            completed = true
            recordStream()
            emit(ProviderEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PrivateOutputProtocolException) {
            recordStream()
            emit(privateOutputFailure(failure.anomaly.name))
        } catch (_: PrivateDispatchPolicyChanged) {
            recordStream()
            emit(
                ProviderEvent.Failed(
                    "Privacy settings or request permissions changed. The response was stopped; resend with the current settings.",
                    errorCode = "DISPATCH_POLICY_CHANGED",
                    taskOutcome = if (inferenceDispatched.get()) "PARTIAL" else "BLOCKED",
                    failureDetails = buildJsonObject {
                        put("failed_stage", if (inferenceDispatched.get()) "generation_permission" else "provider_admission")
                        put("dispatched", inferenceDispatched.get())
                        put("request_replay_allowed", false)
                        put("logical_continuation_allowed", false)
                    }
                )
            )
        } catch (failure: PrivateProviderResponseException) {
            recordStream()
            emit(
                ProviderEvent.Failed(
                    "The private provider response could not be completed. Any partial answer is incomplete. This response was not automatically retried.",
                    errorCode = failure.httpStatus?.let { "HTTP_$it" } ?: "PRIVACY_PROVIDER_RESPONSE_INVALID",
                    taskOutcome = if (inferenceDispatched.get()) "PARTIAL" else "BLOCKED",
                    failureDetails = buildJsonObject {
                        put("failed_stage", "private_provider_response")
                        put("privacy_response_reason", failure.reason.name)
                        failure.httpStatus?.let { put("http_status", it) }
                        put("dispatched", inferenceDispatched.get())
                        put("request_replay_allowed", false)
                        put("logical_continuation_allowed", false)
                        put("usefulness_status", "UNASSESSED")
                    }
                )
            )
        } catch (failure: PrivateDispatchRejected) {
            recordStream()
            emit(privateFinalRequestFailure(failure, currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext], inferenceDispatched.get()))
        } catch (error: Exception) {
            recordStream()
            val decision = (error as? RouteUnavailable)?.decision ?: PrivacyProfileEligibility.Reason.VERIFICATION_UNAVAILABLE.decision()
            val dispatched = inferenceDispatched.get()
            val details = buildJsonObject {
                put("privacy_reason_code", decision.code?.takeIf { code -> PrivacyProfileEligibility.Reason.entries.any { it.name == code } } ?: "VERIFICATION_UNAVAILABLE")
                put("privacy_status", decision.status.name)
                put("failed_stage", if (dispatched) "provider_generation" else "provider_admission")
                put("dispatched", dispatched)
                put("remote_acceptance", if (dispatched) "UNKNOWN" else "NO")
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", false)
            }
            emit(ProviderEvent.Failed(decision.explanation + " " + alternatives(), errorCode = "PRIVACY_PROVIDER_INELIGIBLE", taskOutcome = "BLOCKED", failureDetails = details))
        } finally {
            recordStream()
            answerRestorer?.clear()
            reasoningRestorer?.clear()
            responseId?.let { if (completed) protection?.finishResponse(it) else protection?.discardResponse(it) }
        }
    }

    private class RouteUnavailable(val decision: PrivacyProfileEligibility.Decision) : Exception()

    companion object {
        internal fun synthetic(
            verifier: PrivacyProviderVerifier,
            store: PrivacyModeStore,
            profile: PlatformV2,
            turns: List<ConversationTurn>,
            constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints,
            input: PrivateSyntheticInput,
            alternatives: suspend () -> String
        ): PrivateInferenceSession = PrivateInferenceSession(verifier, store, profile, turns, constraints, alternatives, input)

        /** Network interceptors run outside the flow. Retain permission scopes without inheriting
         * its dispatcher or blocking its Job, and check the original cancellation on both sides.
         */
        internal fun recheckDispatch(context: CoroutineContext, validate: suspend () -> Unit) {
            context.ensureActive()
            kotlinx.coroutines.runBlocking(context.minusKey(kotlinx.coroutines.Job).minusKey(kotlin.coroutines.ContinuationInterceptor)) {
                validate()
            }
            context.ensureActive()
        }

        internal fun wireBody(route: VerifiedPrivateRoute, model: String, messages: JsonArray, limit: Int): JsonObject = buildJsonObject {
            put("model", route.model ?: model)
            if (route.kind != ClientType.LLAMA && route.kind != ClientType.OLLAMA) put("stream", true)
            if (route.kind == ClientType.OPENAI || route.kind == ClientType.OPENROUTER) put("stream_options", buildJsonObject { put("include_usage", true) })
            if (route.kind == ClientType.ANTHROPIC) {
                val items = messages.filterIsInstance<JsonObject>()
                items.firstOrNull { it["role"] == JsonPrimitive("system") }?.get("content")?.let { put("system", it) }
                put("messages", JsonArray(items.filter { it["role"] != JsonPrimitive("system") }))
            } else {
                put("messages", messages)
            }
            put(if (route.kind == ClientType.OPENAI) "max_completion_tokens" else "max_tokens", limit)
            if (route.kind == ClientType.OPENAI) put("store", false)
            if (route.kind == ClientType.OPENROUTER) {
                put(
                    "provider",
                    buildJsonObject {
                        route.providerOptions.forEach { (name, value) -> put(name, value) }
                        put("zdr", true)
                        put("data_collection", "deny")
                        put("allow_fallbacks", false)
                        put("require_parameters", true)
                        val pin = requireNotNull(route.upstream).also { require(it != "*") }
                        put("only", JsonArray(listOf(JsonPrimitive(pin))))
                        put("order", JsonArray(listOf(JsonPrimitive(pin))))
                    }
                )
                // Empty/omitted plugins do not override account defaults. Disable documented
                // overridable plugins explicitly; account evidence must also exclude locked defaults.
                put(
                    "plugins",
                    buildJsonArray {
                        listOf("alignment", "armor", "auto-beta-router", "auto-router", "context-compression", "file-parser", "fusion", "pareto-router", "response-healing", "web").forEach { id ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("enabled", false)
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}
