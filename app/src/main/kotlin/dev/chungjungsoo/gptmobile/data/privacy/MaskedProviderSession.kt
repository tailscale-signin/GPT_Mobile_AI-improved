package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun maskedProviderSession(
    store: PrivacyModeStore,
    profile: PlatformV2,
    turns: List<ConversationTurn>,
    open: suspend (List<ConversationTurn>, PlatformV2) -> AgentProviderSession
): AgentProviderSession = openMaskedProviderSession(store, profile, turns, null, open)

internal suspend fun maskedSyntheticProviderSession(
    store: PrivacyModeStore,
    profile: PlatformV2,
    turns: List<ConversationTurn>,
    input: PrivateSyntheticInput,
    open: suspend (List<ConversationTurn>, PlatformV2) -> AgentProviderSession
): AgentProviderSession = openMaskedProviderSession(store, profile, turns, input, open)

private suspend fun openMaskedProviderSession(
    store: PrivacyModeStore,
    profile: PlatformV2,
    turns: List<ConversationTurn>,
    synthetic: PrivateSyntheticInput?,
    open: suspend (List<ConversationTurn>, PlatformV2) -> AgentProviderSession
): AgentProviderSession {
    val privacy = checkNotNull(currentCoroutineContext()[ConversationPrivacyContext]) { "Conversation privacy policy is unavailable." }
    val state = store.effectiveSnapshot(privacy.enabled)
    check(state.enabled)
    if (state.requireVerifiedRetention) throw UnverifiedServiceTransport()
    val maskingStarted = synthetic?.let { System.nanoTime() }
    val mask = PrivateTextMask(state.categories, synthetic?.privateTerms(profile, turns) ?: store.privateTerms())
    val dispatch = PrivateDispatchContext.Builder(state.revision, state.categories)
    val protection = currentCoroutineContext()[PrivateProtectionContext]
    mask.seedIdentities(listOfNotNull(profile.systemPrompt) + turns.flatMap { listOfNotNull(it.userMessage.content, it.assistantMessage?.content) })
    fun protect(message: MessageV2, kind: PrivateDispatchSurfaceKind): MessageV2 {
        require(message.attachments.isEmpty())
        val protected = mask.protectWithReceipt(message.content)
        dispatch.record(kind, message.content, protected)
        protection?.input(message, protected.receipt)
        return MessageV2(content = protected.text, platformType = message.platformType, id = message.id, chatId = message.chatId)
    }
    val maskedTurns = turns.map {
        it.copy(
            userMessage = protect(it.userMessage, PrivateDispatchSurfaceKind.USER_MESSAGE),
            assistantMessage = it.assistantMessage?.let { message -> protect(message, PrivateDispatchSurfaceKind.ASSISTANT_HISTORY) }
        )
    }
    val maskedSystem = profile.systemPrompt?.let { original ->
        mask.protectWithReceipt(original).also { dispatch.record(PrivateDispatchSurfaceKind.SYSTEM_PROMPT, original, it) }.text
    }
    val issuedMarkers = mask.issuedMarkers()
    val currentRequest = maskedTurns.lastOrNull { it.isCurrentTurn }?.userMessage?.content.orEmpty()
    val maskedProfile = profile.copy(systemPrompt = dispatch.systemPrompt(PrivateMarkerProtocol(issuedMarkers), maskedSystem), batchMode = false, batchApiUrl = null)
    val proof = dispatch.seal(mask.summary(), issuedMarkers)
    val restore = mask.sealWithReceipts()
    if (synthetic != null && maskingStarted != null) synthetic.prepared(proof, System.nanoTime() - maskingStarted)
    val session = open(maskedTurns, maskedProfile)
    return object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            require(tools.isEmpty() && exchanges.isEmpty())
            check(store.effectiveSnapshot(privacy.enabled) == state) { "Privacy settings changed. Resend with the current settings." }
            protection?.flushInputs()
            check(store.effectiveSnapshot(privacy.enabled) == state) { "Privacy settings changed. Resend with the current settings." }
            val authority = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]
            val requestDispatch = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]
            val inheritedObserver = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptObserver]
            val observedDispatch = java.util.concurrent.atomic.AtomicBoolean(false)
            val attemptObserver = dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptObserver { attempt ->
                observedDispatch.set(true)
                inheritedObserver?.onDispatch?.invoke(attempt)
            }
            val privateAuthority = dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard(
                revision = "${authority?.revision.orEmpty()}:privacy-${state.revision}",
                authority = authority?.authority
            ) {
                check(store.effectiveSnapshot(privacy.enabled) == state) { "Privacy settings changed. Resend with the current settings." }
                authority?.validate?.invoke()
                check(store.effectiveSnapshot(privacy.enabled) == state) { "Privacy settings changed. Resend with the current settings." }
            }
            val protocol = PrivateMarkerProtocol(issuedMarkers, currentRequest)
            val answer = PrivateStreamingRestorer(protocol, restore)
            val reasoning = PrivateStreamingRestorer(protocol, restore, assessAnswer = false)
            val timing = PrivateStreamTiming()
            val responseId = protection?.beginResponse()
            val reply = currentCoroutineContext()[PrivateToolReplyMask]
            val control = if (reply != null) StringBuilder() else null
            val deliveryMode = if (reply != null) PrivateDeliveryMode.TOOL_CONTROL_BUFFERED else PrivateDeliveryMode.INCREMENTAL
            var lastLineage = OutputLineage()
            var terminal = false
            var completed = false
            suspend fun validatePublication() {
                currentCoroutineContext().ensureActive()
                try {
                    privateAuthority.validate()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw PrivateDispatchPolicyChanged()
                }
                currentCoroutineContext().ensureActive()
            }
            suspend fun emitAuthorized(event: ProviderEvent) {
                validatePublication()
                emit(event)
            }
            suspend fun publish(result: PrivateProtectedText, lineage: OutputLineage = lastLineage) {
                if (result.text.isEmpty()) return
                // Retained-source and provider permissions can change after dispatch.
                validatePublication()
                timing.restoredAnswer()
                if (responseId != null) protection.restored(responseId, result)
                proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                emit(ProviderEvent.TextDelta(result.text, lineage))
            }
            try {
                session.streamRound(emptyList(), emptyList()).catch { failure ->
                    if (failure !is PrivateDispatchRejected) throw failure
                    // Publish through the ordinary receiving guard below. A typed local refusal
                    // cannot bypass revoked permissions or borrow a prior attempt's no-send claim.
                    emit(privateFinalRequestFailure(failure, requestDispatch, observedDispatch.get()))
                }.flowOn(privateAuthority + proof + attemptObserver).collect { event ->
                    if (terminal) return@collect
                    currentCoroutineContext().ensureActive()
                    if (store.effectiveSnapshot(privacy.enabled) != state) {
                        throw PrivateDispatchPolicyChanged()
                    }
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (event.text.isNotEmpty()) timing.answer()
                            lastLineage = event.lineage
                            if (control != null) {
                                if (event.text.length > 65_536 - control.length) {
                                    terminal = true
                                    proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                                    emit(privateOutputFailure("TOOL_CONTROL_LIMIT", "OUTPUT_PROTOCOL_INVALID"))
                                    throw PrivateStreamStopped
                                } else {
                                    control.append(event.text)
                                }
                            } else {
                                publish(answer.accept(event.text), event.lineage)
                            }
                        }
                        is ProviderEvent.ThinkingDelta -> {
                            if (event.text.isNotEmpty()) timing.reasoning()
                            val restored = reasoning.accept(event.text)
                            if (restored.text.isNotEmpty()) emitAuthorized(ProviderEvent.ThinkingDelta(restored.text))
                        }
                        ProviderEvent.Completed -> {
                            val reasoningTail = reasoning.finish()
                            if (reasoningTail.text.isNotEmpty()) emitAuthorized(ProviderEvent.ThinkingDelta(reasoningTail.text))
                            if (control != null) {
                                val prepared = protocol.prepare(control.toString())
                                val start = System.nanoTime()
                                val restored = requireNotNull(reply).restore(prepared, restore)
                                answer.recordCompleteRestoration(restored, System.nanoTime() - start)
                                publish(restored)
                            }
                            publish(answer.finish())
                            val suspectedOmission = protocol.anomalyCodes().any {
                                it == PrivateOutputAnomaly.POSSIBLE_PROTECTED_FIELD_OMISSION || it == PrivateOutputAnomaly.POSSIBLE_VALUE_REENTRY_REQUEST
                            }
                            if (suspectedOmission) {
                                emitAuthorized(ProviderEvent.Notice("This draft may contain an unfilled protected field or request a value already supplied. Review it before using it; task completeness has not been verified."))
                            }
                            validatePublication()
                            completed = true
                            terminal = true
                            proof.stream(timing.observation(answer, reasoning, protocol, completed = true, deliveryMode = deliveryMode))
                            emit(event)
                            throw PrivateStreamStopped
                        }
                        is ProviderEvent.Failed -> {
                            terminal = true
                            proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                            emitAuthorized(event)
                            throw PrivateStreamStopped
                        }
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult, is ProviderEvent.CombinedAnswer -> {
                            terminal = true
                            proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                            emit(privateOutputFailure("UNEXPECTED_CONTROL_EVENT"))
                            throw PrivateStreamStopped
                        }
                        else -> emitAuthorized(event)
                    }
                }
                if (!terminal) {
                    proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                    emit(privateOutputFailure("MISSING_COMPLETION"))
                }
            } catch (_: PrivateStreamStopped) {
                // The upstream channel is cancelled as soon as its terminal event is published.
            } catch (_: PrivateDispatchPolicyChanged) {
                proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                emit(
                    ProviderEvent.Failed(
                        "Privacy settings or request permissions changed. The response was stopped; resend with the current settings.",
                        errorCode = "DISPATCH_POLICY_CHANGED",
                        taskOutcome = "PARTIAL",
                        failureDetails = buildJsonObject {
                            put("failed_stage", "generation_permission")
                            put("request_replay_allowed", false)
                            put("logical_continuation_allowed", false)
                        }
                    )
                )
            } catch (failure: PrivateOutputProtocolException) {
                proof.stream(timing.observation(answer, reasoning, protocol, completed = false, deliveryMode = deliveryMode))
                emit(privateOutputFailure(failure.anomaly.name))
            } finally {
                proof.stream(timing.observation(answer, reasoning, protocol, completed, deliveryMode))
                answer.clear()
                reasoning.clear()
                control?.clear()
                if (responseId != null) {
                    if (completed) protection.finishResponse(responseId) else protection.discardResponse(responseId)
                }
            }
        }.flowOn(MaskedProviderCall + privacy)
    }
}

private object PrivateStreamStopped : RuntimeException(null, null, false, false)

internal fun privateOutputFailure(reason: String, code: String = "PRIVACY_OUTPUT_PROTOCOL_INVALID") = ProviderEvent.Failed(
    "The private reply could not preserve its local value tokens or response protocol. Any partial answer is incomplete. This response was not automatically retried.",
    errorCode = code,
    taskOutcome = "PARTIAL",
    failureDetails = buildJsonObject {
        put("failed_stage", "private_output_restoration")
        put("privacy_protocol_reason", reason)
        put("request_replay_allowed", false)
        put("logical_continuation_allowed", false)
        put("usefulness_status", "UNASSESSED")
    }
)
