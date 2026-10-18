package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.LlamaCapacityRepository
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Called inside the retry closure immediately before physical HTTP dispatch, after provider transforms. */
internal class ModelRequestPreparation(client: NetworkClient) {
    private val llama = LlamaCapacityRepository(client)

    suspend fun preparePollinationsGet(prompt: String, config: ProviderRequestConfig): String {
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.validate?.invoke()
        // This transport has no output-limit parameter. Do not report the unused JSON DTO cap as transmitted.
        val url = pollinationsPromptUrl(prompt)
        val model = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS.model
        val context = config.contextCeiling?.takeIf { it > 0 && it < Int.MAX_VALUE }?.toLong()
        val runtime = RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl)}", "anonymous", model, model, "unverified", "role-labelled-get-v1", "unverified")
        val capacity = CapacitySnapshot(runtime, context, provenance = if (context == null) CapacityProvenance.UNKNOWN else CapacityProvenance.DECLARED)
        val permission = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.revision ?: config.permissionRevision
        val prepared = RequestAdmission.prepare(url, config.requestRole, capacity, RequestAdmission.estimate(prompt), null, permission, config.requireKnownCapacity, "pollinations-get-v1")
        val wire = prepared.dispatchPayload(runtime, permission)
        val attempt = dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch(prepared.receipt)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.serialized(wire, prepared.receipt.copy(physicalAttemptId = attempt))
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.armHttp()
        return wire
    }

    suspend fun prepare(payload: String, config: ProviderRequestConfig, model: String, outputTokens: Int?): String {
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.validate?.invoke()
        val discovered = if (config.providerType == "LLAMA") llama.discover(config, model) else null
        val catalog = OpenRouterCatalogLimits.forRequest(config.providerType.orEmpty(), config.apiUrl, model)
        val declared = config.contextCeiling?.takeIf { it > 0 && it < Int.MAX_VALUE }?.toLong()
        val context = listOfNotNull(discovered?.contextTokens, catalog?.context?.toLong(), declared).minOrNull()
        val snapshot = discovered?.copy(contextTokens = context) ?: CapacitySnapshot(
            RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl)}", researchHash(config.token.orEmpty()), model, model, "unverified", "unverified", "unverified"),
            context,
            outputTokens = catalog?.output?.toLong(),
            provenance = if (context != null) CapacityProvenance.DECLARED else CapacityProvenance.UNKNOWN
        )
        // Read the actual serialized value as the final authority, not a profile DTO's default.
        val original = Json.parseToJsonElement(payload) as JsonObject
        val reasoning = if (config.providerType == "LLAMA") {
            val needsControl = !config.allowReasoning || config.reasoningMode != dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO
            val control = if (needsControl && discovered != null) llama.reasoningControl(config, model, discovered) else null
            dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy.resolve(config.reasoningMode, config.allowReasoning, control)
        } else {
            null
        }
        val root = if (reasoning != null) dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy.apply(original, reasoning) else original
        val controlledPayload = if (reasoning != null) root.toString() else payload
        val generation = root["generationConfig"] as? JsonObject
        val transmitted = listOfNotNull(root["max_completion_tokens"], root["max_output_tokens"], root["max_tokens"], generation?.get("maxOutputTokens"))
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { count -> count > 0 } }.minOrNull() ?: outputTokens?.takeIf { it > 0 }?.toLong()
        val permissionRevision = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.revision ?: config.permissionRevision
        val allowance = dev.chungjungsoo.gptmobile.data.context.EffectiveOutputAllowance.resolve(transmitted, snapshot.outputTokens, snapshot.contextTokens, RequestAdmission.estimate(controlledPayload))
        val bounded = applyOutputAllowance(root, config.providerType, allowance.tokens)
        var finalPayload = bounded.toString()
        // Inserting the cap itself changes the serialized prompt estimate. Resolve against
        // those exact bytes once more; lowering a positive integer cannot add bytes.
        val finalAllowance = dev.chungjungsoo.gptmobile.data.context.EffectiveOutputAllowance.resolve(allowance.tokens, snapshot.outputTokens, snapshot.contextTokens, RequestAdmission.estimate(finalPayload))
        if (finalAllowance.tokens != allowance.tokens) finalPayload = applyOutputAllowance(bounded, config.providerType, finalAllowance.tokens).toString()
        val measured = RequestAdmission.estimate(finalPayload)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Admission",
            "OUTPUT_ALLOWANCE · role=${config.requestRole} · tokens=${finalAllowance.tokens} · source=${allowance.source} · providerCeiling=${snapshot.outputTokens ?: "unknown"} · providerConfirmed=${snapshot.provenance == CapacityProvenance.VERIFIED_ROUTE}"
        )
        val toolSchema = bounded["tools"]?.toString().orEmpty()
        val toolCount = (bounded["tools"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "ToolCatalog",
            "WIRE_SCHEMA · role=${config.requestRole} · serializedTools=$toolCount · serializedBytes=${toolSchema.toByteArray(Charsets.UTF_8).size} · schemaTokens=${if (toolSchema.isEmpty()) 0 else RequestAdmission.estimate(toolSchema).promptTokens} · measurement=UTF8_ESTIMATE"
        )
        val prepared = RequestAdmission.prepare(finalPayload, config.requestRole, snapshot, measured, finalAllowance.tokens, permissionRevision, requireKnownCapacity = config.requireKnownCapacity, reasoning = reasoning)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Admission",
            "FINAL_REQUEST · role=${config.requestRole} · prompt=${prepared.receipt.promptTokens} · output=${prepared.receipt.outputTokens ?: "unknown"} · safety=${prepared.receipt.safetyTokens} · context=${prepared.receipt.contextTokens ?: "unknown"} · measurement=${prepared.receipt.measurement} · capacity=${prepared.receipt.capacityProvenance} · checked=${prepared.receipt.capacityChecked} · reasoningRequested=${reasoning?.requested} · reasoningEffective=${reasoning?.effective} · reasoningControl=${reasoning?.transmittedControl} · reasoningObserved=unknown"
        )
        val wire = prepared.dispatchPayload(snapshot.fingerprint, permissionRevision)
        val attemptId = dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch(prepared.receipt)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.serialized(wire, prepared.receipt.copy(physicalAttemptId = attemptId))
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.armHttp()
        return wire
    }
}

internal fun applyOutputAllowance(root: JsonObject, provider: String?, tokens: Long): JsonObject {
    require(tokens > 0)
    val value = JsonPrimitive(tokens)
    val existing = listOf("max_completion_tokens", "max_output_tokens", "max_tokens").filter(root::containsKey)
    if (provider == "GOOGLE" || root.containsKey("generationConfig")) {
        val config = root["generationConfig"] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(root + ("generationConfig" to JsonObject(config + ("maxOutputTokens" to value))))
    }
    val keys = existing.ifEmpty {
        listOf(
            when {
                root.containsKey("input") -> "max_output_tokens"
                provider == "OPENAI" -> "max_completion_tokens"
                else -> "max_tokens"
            }
        )
    }
    return JsonObject(root + keys.associateWith { value })
}
