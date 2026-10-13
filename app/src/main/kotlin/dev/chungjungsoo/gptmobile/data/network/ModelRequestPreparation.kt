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

    suspend fun prepare(payload: String, config: ProviderRequestConfig, model: String, outputTokens: Int?): String {
        val discovered = if (config.providerType == "LLAMA") llama.discover(config, model) else null
        val catalog = OpenRouterCatalogLimits.forRequest(config.providerType.orEmpty(), config.apiUrl, model)
        val declared = config.contextCeiling?.takeIf { it > 0 && it < Int.MAX_VALUE }?.toLong()
        val context = listOfNotNull(discovered?.contextTokens, catalog?.context?.toLong(), declared).minOrNull()
        val snapshot = discovered?.copy(contextTokens = context) ?: CapacitySnapshot(
            RuntimeFingerprint(config.profileUid ?: "unbound", config.apiUrl, researchHash(config.token.orEmpty()), model, model, "unverified", "unverified", "unverified"),
            context,
            outputTokens = catalog?.output?.toLong(),
            provenance = if (context != null) CapacityProvenance.DECLARED else CapacityProvenance.UNKNOWN
        )
        // Read the actual serialized value as the final authority, not a profile DTO's default.
        val root = Json.parseToJsonElement(payload) as JsonObject
        val generation = root["generationConfig"] as? JsonObject
        val transmitted = listOfNotNull(root["max_completion_tokens"], root["max_output_tokens"], root["max_tokens"], generation?.get("maxOutputTokens"))
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { count -> count > 0 } }.minOrNull() ?: outputTokens?.takeIf { it > 0 }?.toLong()
        val measured = RequestAdmission.estimate(payload)
        val prepared = RequestAdmission.prepare(payload, config.requestRole, snapshot, measured, transmitted, config.permissionRevision, requireKnownCapacity = config.requireKnownCapacity)
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Admission",
            "FINAL_REQUEST · role=${config.requestRole} · prompt=${prepared.receipt.promptTokens} · output=${prepared.receipt.outputTokens ?: "unknown"} · safety=${prepared.receipt.safetyTokens} · context=${prepared.receipt.contextTokens ?: "unknown"} · measurement=${prepared.receipt.measurement} · capacity=${prepared.receipt.capacityProvenance} · checked=${prepared.receipt.capacityChecked}"
        )
        val wire = prepared.dispatchPayload(snapshot.fingerprint, config.permissionRevision)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]?.serialized(wire, prepared.receipt)
        return wire
    }
}
