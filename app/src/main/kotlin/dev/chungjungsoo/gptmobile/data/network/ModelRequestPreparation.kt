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
    private val llamaHealth = dev.chungjungsoo.gptmobile.data.llama.LlamaHealthDiagnostics(client)

    suspend fun prepareQueued(payload: String, config: ProviderRequestConfig, model: String, outputTokens: Int?): String {
        config.requireSelectedRoute(model)
        val context = kotlinx.coroutines.currentCoroutineContext()
        context[WorkerRequestBudget]?.preflight()
        context[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.validate?.invoke()
        val revision = context[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.revision ?: config.permissionRevision
        dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOutputBudget.requireCapacity(config.requestRole, outputTokens?.toLong() ?: 0)
        val runtime = RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl)}", "anonymous", model, model, "unverified", "queued-native-v1", "unverified")
        val capacity = RequestAdmission.auxiliaryCapacity(config.requestRole, CapacitySnapshot(runtime, config.contextCeiling?.takeIf { it > 0 && it < Int.MAX_VALUE }?.toLong(), provenance = CapacityProvenance.UNKNOWN))
        val measurement = RequestAdmission.estimate(payload)
        val budget = dev.chungjungsoo.gptmobile.data.context.PromptBudgets.capture(payload, measurement, outputTokens?.toLong(), capacity.contextTokens, runtime.templateRevision)
        val prepared = RequestAdmission.prepare(payload, config.requestRole, capacity, measurement, outputTokens?.toLong(), revision, config.requireKnownCapacity, "queued-native-v1", promptBudget = budget, requestDeadline = context[WorkerRequestBudget]?.preflight())
        config.freeDiagnostics?.admission = prepared.receipt
        prepared.receipt.outputTokens?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()?.let { config.onSerializedOutputTokens?.invoke(it) }
        val wire = prepared.dispatchPayload(runtime, revision)
        val attempt = dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch(prepared.receipt)
        context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.serialized(wire, prepared.receipt.copy(physicalAttemptId = attempt), config)
        context[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.armHttp()
        return wire
    }

    suspend fun prepare(payload: String, config: ProviderRequestConfig, model: String, outputTokens: Int?): String {
        config.requireSelectedRoute(model)
        kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]?.preflight()
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.validate?.invoke()
        dev.chungjungsoo.gptmobile.data.context.ProviderMessageValidator.validate(payload, config.intentionalAssistantPrefill)
        val discovered = if (config.providerType == "LLAMA") llama.discover(config, model) else llama.discoverGateway(config, model)
        val gatewayObservation = if (config.providerType == "LLAMA") llama.gatewayObservation(config, model) else null
        if (config.providerType == "LLAMA" && discovered == null) {
            val dispatch = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]
            val diagnosticFields = if (gatewayObservation != null) {
                // Exact-route metadata already explains this rejection. A second
                // readiness round trip cannot qualify the missing template proof.
                mapOf("phase" to "DEGRADED", "metadataSource" to "GATEWAY_CAPABILITIES", "skippedMetadataSurfaces" to listOf("health", "props", "models"), "inferenceTested" to false) + gatewayObservation.diagnosticFields()
            } else {
                llamaHealth.inspect(config, model).diagnosticFields()
            }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.recordEvent(
                "LocalConnection",
                "LLAMA_READINESS",
                diagnosticFields + mapOf("runId" to dispatch?.diagnosticRunId, "invocationId" to dispatch?.diagnosticInvocationId),
                "W"
            )
        }
        val catalog = OpenRouterCatalogLimits.forRequest(config.providerType.orEmpty(), config.apiUrl, model)
        val freeModel = config.freeModelSnapshot?.takeIf { it.id == model }
            ?: config.freeProvider?.let { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.model(it, model) }
        val declared = config.contextCeiling?.takeIf { it > 0 && it < Int.MAX_VALUE }?.toLong()
        val context = listOfNotNull(catalog?.context?.toLong(), freeModel?.contextTokens?.toLong(), declared).minOrNull()
        val output = listOfNotNull(catalog?.output?.toLong(), freeModel?.outputTokens?.toLong(), config.freeProvider?.enforcedOutputCeiling?.toLong()).minOrNull()
        val snapshot = RequestAdmission.auxiliaryCapacity(
            config.requestRole,
            (
                discovered ?: CapacitySnapshot(
                    RuntimeFingerprint(config.profileUid ?: "unbound", "sha256:${researchHash(config.apiUrl)}", researchHash(config.token.orEmpty()), model, model, "unverified", "unverified", "unverified"),
                    context,
                    provenance = if (context != null) CapacityProvenance.DECLARED else CapacityProvenance.UNKNOWN
                )
                ).withCeilings(context, output)
        )
        // Read the actual serialized value as the final authority, not a profile DTO's default.
        var original = Json.parseToJsonElement(payload) as JsonObject
        config.requireSelectedRoute((original["model"] as? JsonPrimitive)?.content ?: model)
        val review = config.requestRole.startsWith("github-review") && !config.requestRole.endsWith("repair")
        val briefHelper = review || config.requestRole in setOf("github-claim-extraction", "github-progress-summary", "answer_contract_rewrite", "synthesis")
        val reasoning = if (config.providerType == "LLAMA") {
            val needsControl = briefHelper || config.maximizeReasoning || !config.allowReasoning || config.reasoningMode != dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO
            val control = if (needsControl && discovered != null) llama.reasoningControl(config, model, discovered) else null
            val strict = !config.allowReasoning || config.reasoningMode != dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO
            val gatewayControlUnverified = discovered?.fingerprint?.templateRevision?.startsWith("gateway:") == true &&
                gatewayObservation?.reasoningControlState != "UNSUPPORTED"
            if (strict && control == null && (discovered == null || gatewayControlUnverified)) {
                val reason = when {
                    gatewayObservation?.failureCode == "GATEWAY_CAPABILITY_TIMEOUT" || gatewayObservation?.failureCategory in setOf("connect_timeout", "read_timeout", "deadline", "CONNECT_TIMEOUT", "REQUEST_TIMEOUT", "STREAM_IDLE_TIMEOUT") -> dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_TIMEOUT
                    gatewayObservation?.failureCode != null -> dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_UNAVAILABLE
                    else -> dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_CAPABILITY_UNVERIFIED
                }
                kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.rejectedBeforeInference(reason)
                throw dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException(reason.name, gatewayObservation)
            }
            try {
                dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy.forRequest(
                    config.reasoningMode,
                    config.allowReasoning,
                    config.maximizeReasoning,
                    briefHelper,
                    control
                )
            } catch (rejected: dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException) {
                // This precedes both the optional schema inference probe and physical request
                // admission. Metadata/template HTTP exchanges are not inference dispatches.
                val reason = when (rejected.requested) {
                    dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF -> dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_OFF_UNSUPPORTED
                    else -> dev.chungjungsoo.gptmobile.data.context.InferencePreflightReason.REASONING_ON_UNSUPPORTED
                }
                kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.rejectedBeforeInference(reason)
                throw rejected
            }
        } else {
            null
        }
        // Qualify strict controls before an optional schema probe can execute inference.
        if (review && config.providerType == "LLAMA" && llama.supportsReviewSchema(config, model, discovered)) {
            original = JsonObject(original + ("response_format" to dev.chungjungsoo.gptmobile.data.github.GitHubReviewSchema.format))
        }
        val root = if (reasoning != null) dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy.apply(original, reasoning) else original
        val controlledPayload = if (reasoning != null) root.toString() else payload
        val generation = root["generationConfig"] as? JsonObject
        val transmitted = listOfNotNull(root["max_completion_tokens"], root["max_output_tokens"], root["max_tokens"], generation?.get("maxOutputTokens"))
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { count -> count > 0 } }.minOrNull() ?: outputTokens?.takeIf { it > 0 }?.toLong()
        val permissionRevision = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.revision ?: config.permissionRevision
        val allowance = resolveOutputAllowance(controlledPayload, transmitted, snapshot)
        val bounded = applyOutputAllowance(root, config.providerType, allowance.tokens)
        var finalPayload = bounded.toString()
        // Inserting the cap itself changes the serialized prompt estimate. Resolve against
        // those exact bytes once more; lowering a positive integer cannot add bytes.
        val finalAllowance = resolveOutputAllowance(finalPayload, allowance.tokens, snapshot)
        if (finalAllowance.tokens != allowance.tokens) finalPayload = applyOutputAllowance(bounded, config.providerType, finalAllowance.tokens).toString()
        if (config.freeProvider == dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS) PollinationsClassic.requireOutputAllowance(finalAllowance.tokens)
        dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOutputBudget.requireCapacity(config.requestRole, finalAllowance.tokens)
        dev.chungjungsoo.gptmobile.data.context.ProviderMessageValidator.validate(finalPayload, config.intentionalAssistantPrefill)
        val measured = RequestAdmission.estimate(finalPayload)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Admission",
            "OUTPUT_ALLOWANCE · role=${config.requestRole} · tokens=${finalAllowance.tokens} · source=${allowance.source} · providerCeiling=${discovered?.outputTokens ?: "unknown"} · effectiveCeiling=${snapshot.outputTokens ?: "unknown"} · providerConfirmed=${discovered?.provenance == CapacityProvenance.VERIFIED_ROUTE && discovered?.outputTokens != null}"
        )
        val toolSchema = bounded["tools"]?.toString().orEmpty()
        val toolNames = wireToolNames(bounded)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "ToolCatalog",
            "WIRE_SCHEMA · role=${config.requestRole} · serializedTools=${toolNames.size} · names=${toolNames.joinToString()} · serializedBytes=${toolSchema.toByteArray(Charsets.UTF_8).size} · schemaTokens=${if (toolSchema.isEmpty()) 0 else RequestAdmission.estimate(toolSchema).promptTokens} · measurement=UTF8_ESTIMATE"
        )
        val budget = dev.chungjungsoo.gptmobile.data.context.PromptBudgets.capture(finalPayload, measured, finalAllowance.tokens, snapshot.contextTokens, snapshot.fingerprint.templateRevision)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Admission", "RUNTIME_IDENTITY · ${snapshot.fingerprint} · reasoningAllowed=${config.allowReasoning} · requestedMode=${config.reasoningMode} · toolReserveState=serialized_results_replace_planning_estimate · usage=Not measured until server receipt")
        val prepared = RequestAdmission.prepare(finalPayload, config.requestRole, snapshot, measured, finalAllowance.tokens, permissionRevision, requireKnownCapacity = config.requireKnownCapacity, serializerRevision = if (config.freeProvider == dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.POLLINATIONS) PollinationsClassic.SERIALIZER_REVISION else "provider-json-v1", reasoning = reasoning, messageValidatorRevision = dev.chungjungsoo.gptmobile.data.context.ProviderMessageValidator.VERSION, promptBudget = budget, requestDeadline = kotlinx.coroutines.currentCoroutineContext()[WorkerRequestBudget]?.preflight())
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Admission",
            "FINAL_REQUEST · role=${config.requestRole} · promptTokens=${prepared.receipt.promptTokens} · outputTokens=${prepared.receipt.outputTokens ?: "unknown"} · safety=${prepared.receipt.safetyTokens} · contextTokens=${prepared.receipt.contextTokens ?: "unknown"} · measurement=${prepared.receipt.measurement} · capacity=${prepared.receipt.capacityProvenance} · checked=${prepared.receipt.capacityChecked} · reasoningRequested=${reasoning?.requested} · reasoningEffective=${reasoning?.effective} · reasoningControl=${reasoning?.transmittedControl} · reasoningObserved=unknown"
        )
        config.freeDiagnostics?.admission = prepared.receipt
        prepared.receipt.outputTokens?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()?.let { config.onSerializedOutputTokens?.invoke(it) }
        val wire = prepared.dispatchPayload(snapshot.fingerprint, permissionRevision)
        val attemptId = dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary.dispatch(prepared.receipt)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.serialized(wire, prepared.receipt.copy(physicalAttemptId = attemptId), config)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.armHttp()
        return wire
    }
}

internal fun resolveOutputAllowance(payload: String, requested: Long?, capacity: CapacitySnapshot): dev.chungjungsoo.gptmobile.data.context.EffectiveOutputAllowance.Decision {
    val measurement = RequestAdmission.estimate(payload)
    return try {
        dev.chungjungsoo.gptmobile.data.context.EffectiveOutputAllowance.resolve(requested, capacity.outputTokens, capacity.contextTokens, measurement)
    } catch (rejected: IllegalStateException) {
        val budget = dev.chungjungsoo.gptmobile.data.context.PromptBudgets.capture(payload, measurement, requested, capacity.contextTokens, capacity.fingerprint.templateRevision)
        throw IllegalStateException("${rejected.message}. ${budget.explain()}. Reduce the dominant input component or choose a verified larger context.", rejected)
    }
}

/** Names from the final serialized request, including Gemini's grouped declarations. */
internal fun wireToolNames(root: JsonObject): List<String> = (root["tools"] as? kotlinx.serialization.json.JsonArray).orEmpty().flatMap { entry ->
    val tool = entry as? JsonObject ?: return@flatMap emptyList()
    val declarations = tool["functionDeclarations"] as? kotlinx.serialization.json.JsonArray
    if (declarations != null) {
        declarations.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
    } else {
        listOfNotNull(((tool["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: (tool["name"] as? JsonPrimitive)?.content ?: (tool["type"] as? JsonPrimitive)?.content)
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
