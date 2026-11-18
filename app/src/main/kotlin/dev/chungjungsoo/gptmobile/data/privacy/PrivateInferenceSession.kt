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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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

internal object PrivateTurn : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<PrivateTurn>
}

internal class PrivateInferenceSession(
    private val verifier: PrivacyProviderVerifier,
    private val store: PrivacyModeStore,
    private val profile: PlatformV2,
    private val turns: List<ConversationTurn>,
    private val constraints: dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints,
    private val alternatives: suspend () -> String
) : AgentProviderSession {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
        try {
            check(tools.isEmpty() && exchanges.isEmpty()) { "External tools require separate privacy verification." }
            val state = store.snapshot()
            check(state.enabled) { "Privacy settings changed. Resend to confirm the new settings." }
            val mask = PrivateTextMask(state.categories, store.privateTerms())
            val messages = buildJsonArray {
                fun message(role: String, text: String) {
                    if (text.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", role)
                                put("content", mask.protect(text))
                            }
                        )
                    }
                }
                profile.systemPrompt?.let { message("system", it) }
                turns.forEach { turn ->
                    check(turn.userMessage.attachments.isEmpty() && turn.assistantMessage?.attachments.isNullOrEmpty()) { "Attachments require separate privacy verification." }
                    message("user", turn.userMessage.content)
                    turn.assistantMessage?.let { message("assistant", it.content) }
                }
            }
            val preferred = constraints.outputLimit(profile.maxTokens) ?: 4096
            val limit = if (profile.compatibleType == ClientType.FREE) dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.requireFor(profile).outputAllowance(preferred) else preferred
            // The response map exists only for this invocation. No plaintext mapping is persisted.
            val restore = mask.sealForResponse()
            currentCoroutineContext()[RequestAuthorizationGuard]?.validate?.invoke()
            suspend fun transmit(): JsonObject {
                // Verify after acquiring the free-provider permit. Queued work cannot reuse
                // the eligibility decision made when the conversation was opened.
                val verified = verifier.verify(profile)
                val route = verified.route ?: throw RouteUnavailable(verified.decision.explanation)
                val body = wireBody(route, profile.model, messages, limit)
                val config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig(
                    apiUrl = route.url, token = null, profileUid = profile.uid, providerType = profile.compatibleType.name,
                    lockedApiUrl = route.url, lockedModel = profile.model, contextCeiling = constraints.contextCeiling,
                    requireKnownCapacity = constraints.requireKnownCapacity, permissionRevision = constraints.permissionRevision,
                    requestRole = constraints.requestRole ?: "primary"
                )
                val wire = verifier.preparation.prepareQueued(body.toString(), config, profile.model, limit)
                val request = Request.Builder().url(route.url).apply {
                    if (route.kind == ClientType.ANTHROPIC) {
                        header("x-api-key", profile.token.orEmpty())
                        header("anthropic-version", "2023-06-01")
                    } else {
                        profile.token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
                    }
                    header("Cache-Control", "no-store")
                }.post(wire.toRequestBody("application/json".toMediaType())).build()
                // Recheck on the network thread just before the body can be written.
                val attempted = java.util.concurrent.atomic.AtomicBoolean(false)
                val client = verifier.http.newBuilder().addNetworkInterceptor { chain ->
                    val proof = route.approval
                    if (!attempted.compareAndSet(false, true) || store.snapshot() != state || (proof != null && verifier.approval(profile, route.url, proof.policyUrl) != proof)) {
                        throw IOException("Privacy verification changed before dispatch.")
                    }
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
            emit(ProviderEvent.TextDelta(restore(text)))
            emit(ProviderEvent.Completed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? RouteUnavailable)?.reason ?: "Private inference stopped. Your conversation is saved locally."
            emit(ProviderEvent.Failed(reason + " " + alternatives(), errorCode = "PRIVACY_PROVIDER_INELIGIBLE", taskOutcome = "BLOCKED"))
        }
    }

    private class RouteUnavailable(val reason: String) : Exception()

    companion object {
        internal fun wireBody(route: VerifiedPrivateRoute, model: String, messages: JsonArray, limit: Int): JsonObject = buildJsonObject {
            put("model", model)
            if (route.kind == ClientType.ANTHROPIC) {
                val items = messages.filterIsInstance<JsonObject>()
                items.firstOrNull { it["role"] == JsonPrimitive("system") }?.get("content")?.let { put("system", it) }
                put("messages", JsonArray(items.filter { it["role"] != JsonPrimitive("system") }))
            } else {
                put("messages", messages)
            }
            put(if (route.kind == ClientType.OPENAI) "max_completion_tokens" else "max_tokens", limit)
            if (route.kind == ClientType.OPENAI || route.kind == ClientType.OPENROUTER) put("store", false)
            if (route.kind == ClientType.OPENROUTER) {
                put(
                    "provider",
                    buildJsonObject {
                        put("zdr", true)
                        put("data_collection", "deny")
                        put("allow_fallbacks", false)
                        put("only", JsonArray(listOf(JsonPrimitive(requireNotNull(route.approval).upstream))))
                    }
                )
            }
        }
    }
}
