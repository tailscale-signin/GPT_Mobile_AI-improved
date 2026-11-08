package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Stable selection contains references only. Boot, lease and credential generations are attempt evidence. */
@Serializable
data class GatewayExecutionSelection(
    @SerialName("profile_uid") val profileUid: String,
    @SerialName("profile_revision") val profileRevision: String,
    @SerialName("provider_connection_id") val providerConnectionId: String,
    @SerialName("provider_connection_revision") val providerConnectionRevision: String,
    @SerialName("provider_kind") val providerKind: String,
    @SerialName("configured_endpoint") val configuredEndpoint: String,
    @SerialName("requested_model_id") val requestedModelId: String,
    @SerialName("generation_parameters") val generationParameters: JsonObject,
    @SerialName("privacy_scope") val privacyScope: String,
    @SerialName("tool_owner") val toolOwner: String = "GATEWAY",
    @SerialName("required_capability_policy") val requiredCapabilityPolicy: List<String> = listOf("SELECTED_ONLY", "MODEL_IDENTITY", "CONTEXT_BUDGET"),
    @SerialName("credential_reference") val credentialReference: String? = null,
    @SerialName("credential_account_identity") val credentialAccountIdentity: String? = null
)

@Serializable
data class GatewayExecutionAdmission(val binding: JsonObject, val capabilities: JsonObject) {
    val bindingId: String get() = binding["binding_id"]?.jsonPrimitive?.content.orEmpty()

    fun verify(selection: GatewayExecutionSelection) {
        verifySelection(selection)
        check(capabilities["requested_model_id"]?.jsonPrimitive?.content == selection.requestedModelId && capabilities["reported_model_id"]?.jsonPrimitive?.content == selection.requestedModelId) { "The configured backend did not prove the exact selected model identity." }
    }

    /** Local identity comparison does not claim fresh backend capability evidence. */
    fun verifySelection(selection: GatewayExecutionSelection) {
        check(bindingId.matches(Regex("bind_[a-f0-9]{32}"))) { "The gateway execution binding is invalid." }
        val expected = mapOf(
            "routing_policy" to "SELECTED_ONLY", "profile_uid" to selection.profileUid,
            "profile_revision" to selection.profileRevision, "provider_connection_id" to selection.providerConnectionId,
            "provider_connection_revision" to selection.providerConnectionRevision, "provider_kind" to selection.providerKind,
            "requested_model_id" to selection.requestedModelId, "privacy_scope" to selection.privacyScope,
            "tool_owner" to selection.toolOwner,
            "configured_endpoint_identity" to selectionDigest(JsonPrimitive(selection.configuredEndpoint))
        )
        check(expected.all { (key, value) -> binding[key]?.jsonPrimitive?.content == value }) { "The gateway substituted part of the selected execution identity." }
        check(binding["generation_parameters"] == selection.generationParameters) { "The gateway changed the selected generation parameters." }
    }
}

internal fun executionSelection(profile: PlatformV2, connection: ProviderConnection?, privacyScope: String): GatewayExecutionSelection {
    require(GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile))
    check(profile.providerConnectionUid == null || connection?.uid == profile.providerConnectionUid) { "The selected provider connection is unavailable." }
    val endpoint = selectedEndpoint(profile.apiUrl)
    if (connection != null) {
        check(connection.compatibleType == profile.compatibleType && selectedEndpoint(connection.apiUrl) == endpoint) { "The selected profile and provider connection disagree." }
    }
    val parameters = buildJsonObject {
        profile.temperature?.let { put("temperature", it) }
        profile.topP?.let { put("top_p", it) }
        profile.topK?.let { put("top_k", it) }
        profile.maxTokens?.let { put("max_tokens", it) }
        put("reasoning", profile.reasoning)
        profile.ollamaOptions?.takeIf(String::isNotBlank)?.let { raw ->
            require(raw.length <= 8192) { "Selected model options exceed their bound." }
            val options = Json.parseToJsonElement(raw) as? JsonObject ?: error("Selected model options are invalid.")
            val names = mapOf("num_ctx" to "context_tokens", "num_predict" to "max_tokens", "temperature" to "temperature", "top_p" to "top_p", "top_k" to "top_k", "repeat_penalty" to "repeat_penalty", "seed" to "seed")
            check(options.keys.all(names::containsKey)) { "This strict gateway cannot enforce every selected model option." }
            options.forEach { (key, value) -> put(requireNotNull(names[key]), value) }
        }
    }
    val connectionId = connection?.uid ?: "profile:${profile.uid}"
    val credentialRef = connection?.secretRef ?: profile.secretRef
    val connectionRevision = selectionDigest(
        buildJsonObject {
            put("id", connectionId)
            put("endpoint", endpoint)
            put("provider", profile.compatibleType.name)
            put("credential_reference", credentialRef?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        }
    )
    val revision = selectionDigest(
        buildJsonObject {
            put("uid", profile.uid)
            put("connection_revision", connectionRevision)
            put("model", profile.model)
            put("parameters", parameters)
            put("system_prompt", profile.systemPrompt?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            put("accelerator", profile.accelerator?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        }
    )
    return GatewayExecutionSelection(profile.uid, revision, connectionId, connectionRevision, profile.compatibleType.name, endpoint, profile.model, parameters, privacyScope, credentialReference = credentialRef)
}

internal fun selectedEndpoint(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    val path = uri.rawPath.orEmpty().trimEnd('/')
    require(path.split('/').none { it == "." || it == ".." } && '\\' !in path)
    val host = uri.host.lowercase().let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
    return "${uri.scheme.lowercase()}://$host${if (uri.port >= 0) ":${uri.port}" else ""}$path"
}

internal fun selectionDigest(value: JsonElement): String {
    fun ordered(item: JsonElement): JsonElement = when (item) {
        is JsonObject -> JsonObject(item.toSortedMap().mapValues { ordered(it.value) })
        is JsonArray -> JsonArray(item.map(::ordered))
        else -> item
    }
    return MessageDigest.getInstance("SHA-256").digest(ordered(value).toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
