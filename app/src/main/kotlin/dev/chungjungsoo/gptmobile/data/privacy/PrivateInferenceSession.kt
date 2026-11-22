package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class PrivateInferenceSession(
    private val verifier: PrivacyProviderVerifier,
    private val store: PrivacyModeStore,
    private val profile: PlatformV2,
    private val turns: List<ConversationTurn>,
    private val constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints,
    private val alternatives: suspend () -> String
) : AgentProviderSession {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
        val inferenceDispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            check(tools.isEmpty() && exchanges.isEmpty()) { "External tools require separate privacy verification." }
            check(PrivacyModeStore.active()) { "This request has no private conversation policy." }
            val state = store.effectiveSnapshot(true)
            val mask = PrivateTextMask(state.categories, store.privateTerms())
            val protection = currentCoroutineContext()[PrivateProtectionContext]
            mask.seedIdentities(listOfNotNull(profile.systemPrompt) + turns.flatMap { listOfNotNull(it.userMessage.content, it.assistantMessage?.content) })
            val messages = buildJsonArray {
                fun message(role: String, text: String, source: dev.chungjungsoo.gptmobile.data.database.entity.MessageV2? = null) {
                    if (text.isNotBlank()) {
                        val protected = mask.protectWithReceipt(text)
                        source?.let { protection?.input(it, protected.receipt) }
                        add(
                            buildJsonObject {
                                put("role", role)
                                put("content", protected.text)
                            }
                        )
                    }
                }
                profile.systemPrompt?.let { message("system", it) }
                turns.forEach { turn ->
                    check(turn.userMessage.attachments.isEmpty() && turn.assistantMessage?.attachments.isNullOrEmpty()) { "Attachments require separate privacy verification." }
                    message("user", turn.userMessage.content, turn.userMessage)
                    turn.assistantMessage?.let { message("assistant", it.content, it) }
                }
            }
            val preferred = constraints.outputLimit(profile.maxTokens) ?: 4096
            val limit = if (profile.compatibleType == ClientType.FREE) dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.requireFor(profile).outputAllowance(preferred) else preferred
            // The response map exists only for this invocation. No plaintext mapping is persisted.
            val restore = mask.sealWithReceipts()
            val originalContext = currentCoroutineContext()
            val authorization = originalContext[RequestAuthorizationGuard]
            authorization?.validate?.invoke()
            suspend fun transmit(): JsonObject {
                // Verify after acquiring the free-provider permit. Queued work cannot reuse
                // the eligibility decision made when the conversation was opened.
                val verified = verifier.verify(profile)
                val route = verified.route ?: throw RouteUnavailable(verified.decision)
                val requestModel = route.model ?: profile.model
                val body = wireBody(route, requestModel, messages, limit)
                val config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig(
                    apiUrl = route.url, token = null, profileUid = profile.uid, providerType = profile.compatibleType.name,
                    lockedApiUrl = route.url, lockedModel = requestModel, contextCeiling = constraints.contextCeiling,
                    requireKnownCapacity = constraints.requireKnownCapacity, permissionRevision = constraints.permissionRevision,
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
                val client = verifier.http.newBuilder().addNetworkInterceptor { chain ->
                    val proof = route.approval
                    if (!attempted.compareAndSet(false, true)) {
                        throw IOException("Privacy verification changed before dispatch.")
                    }
                    try {
                        recheckDispatch(originalContext) {
                            check(verifier.routingUnchanged(profile, route))
                            check(verifier.accountUnchanged(profile, route))
                            authorization?.validate?.invoke()
                            check(store.effectiveSnapshot(true) == state)
                        }
                        check(proof == null || verifier.approval(profile, route.url, proof.policyUrl) == proof)
                    } catch (_: Exception) {
                        throw IOException("The profile or its permissions changed before dispatch.")
                    }
                    inferenceDispatched.set(true)
                    chain.proceed(chain.request())
                }.build()
                return verifier.readJson(client, request)
            }
            val result = if (profile.compatibleType == ClientType.FREE) {
                val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.requireFor(profile)
                check(dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile) == null) { "Free provider unavailable." }
                dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter.shared.withRequest(provider, profile.model, "anonymous") { transmit() }
            } else {
                transmit()
            }
            val answer = when (profile.compatibleType) {
                ClientType.LLAMA, ClientType.OLLAMA -> result["text"] as? JsonPrimitive
                ClientType.ANTHROPIC -> (result["content"] as? JsonArray)?.singleOrNull()?.let { it as? JsonObject }?.takeIf { it["type"] == JsonPrimitive("text") }?.get("text") as? JsonPrimitive
                else -> ((result["choices"] as? JsonArray)?.singleOrNull() as? JsonObject)?.get("message")?.let { it as? JsonObject }?.get("content") as? JsonPrimitive
            }
            val text = checkNotNull(answer?.takeIf { it.isString }?.contentOrNull) { "The private endpoint returned no text." }
            val usage = result["usage"] as? JsonObject
            if (usage != null) {
                fun count(vararg fields: String) = fields.firstNotNullOfOrNull { field ->
                    (usage[field] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
                }
                emit(ProviderEvent.Usage(inputTokens = count("prompt_tokens", "input_tokens"), outputTokens = count("completion_tokens", "output_tokens"), totalTokens = count("total_tokens")))
            }
            val restored = currentCoroutineContext()[PrivateToolReplyMask]?.restore(text, restore) ?: restore(text)
            protection?.restored(restored)
            emit(ProviderEvent.TextDelta(restored.text))
            emit(ProviderEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
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
        }
    }

    private class RouteUnavailable(val decision: PrivacyProfileEligibility.Decision) : Exception()

    companion object {
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
