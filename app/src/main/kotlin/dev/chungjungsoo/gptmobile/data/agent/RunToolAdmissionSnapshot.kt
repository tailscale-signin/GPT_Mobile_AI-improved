package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class RunToolAdmissionStage { STAGE_NOT_REACHED, RESOLVED, ADMITTED }

/** Compact evidence of this run's preparation, persisted in the protected workspace store.
 * Schema descriptions, credentials and task text are never included. Empty and unreached differ. */
internal object RunToolAdmissionSnapshot {
    private const val MAX_DETAILS = 256
    private val safeLabel = Regex("[A-Za-z0-9_.:-]{1,160}")
    private val omissionReasons = setOf("NOT_NEEDED", "USER_DISABLED", "UNSUPPORTED", "CONTEXT_BUDGET", "REQUIRED_UNAVAILABLE", "REMAINING_CONTEXT_BUDGET", "CATALOG_NOT_SELECTED", "DEFERRED_NOT_REQUESTED", "SCHEMA_TOO_LARGE", "TOOL_COUNT_WINDOW", "SCHEMA_WINDOW_BUDGET", "AMBIGUOUS_MODEL_ALIAS", "PLUGIN_DISABLED", "FREE_PROFILE_CONSENT_REQUIRED", "CHAT_TOOL_SELECTION")

    fun capture(
        runId: String,
        profileUid: String,
        providerConnectionUid: String?,
        stage: RunToolAdmissionStage,
        inventory: List<AgentToolDefinition>? = null,
        accepted: List<AgentToolDefinition>? = null,
        omissions: Map<String, String> = emptyMap(),
        toolConnections: Map<String, String?> = emptyMap(),
        observedAtMillis: Long = System.currentTimeMillis()
    ): JsonObject {
        require(stage != RunToolAdmissionStage.STAGE_NOT_REACHED || inventory == null && accepted == null && omissions.isEmpty())
        require(stage == RunToolAdmissionStage.STAGE_NOT_REACHED || inventory != null)
        require(stage != RunToolAdmissionStage.ADMITTED || accepted != null)
        val catalog = inventory?.map { definition ->
            buildJsonObject {
                put("name", label(definition.name))
                put("schemaDigest", ToolContractFingerprint.schema(definition.inputSchema))
                put("connectionUid", toolConnections[definition.name]?.let(::label))
                put("modelVisible", definition.modelVisible)
            }
        }?.sortedBy(JsonObject::toString)
        val selected = accepted?.map { label(it.name) }?.sorted()
        val omitted = omissions.toSortedMap()
        val reasons = omitted.values.groupingBy { reason(it) }.eachCount().toSortedMap()
        val catalogDigest = catalog?.let { digest(JsonArray(it).toString()) }
        return buildJsonObject {
            put("schema", "gptmobile.run-tool-admission.v1")
            put("kind", "run_tool_admission")
            put("runId", label(runId))
            put("profileUid", label(profileUid))
            put("providerConnectionUid", providerConnectionUid?.let(::label))
            put("preparationStage", stage.name)
            put("observedAtMillis", observedAtMillis)
            put("catalogStatus", if (inventory == null) "STAGE_NOT_REACHED" else "OBSERVED")
            put("catalogDigest", catalogDigest)
            put("catalogDigestScope", "ALL_ORDER_INDEPENDENT_TOOL_NAMES_CONTRACT_SCHEMAS_CONNECTIONS_AND_VISIBILITY")
            put("inventoryCount", inventory?.size)
            put("inventoryScope", "PROVIDED_STAGE_INVENTORY")
            put("inventory", catalog?.let { JsonArray(it.take(MAX_DETAILS)) } ?: JsonNull)
            put("acceptedStatus", if (accepted == null) "STAGE_NOT_REACHED" else "OBSERVED")
            put("acceptedCount", accepted?.size)
            put("acceptedTools", selected?.let { JsonArray(it.take(MAX_DETAILS).map(::JsonPrimitive)) } ?: JsonNull)
            put("acceptedDigest", selected?.let { digest(JsonArray(it.map(::JsonPrimitive)).toString()) })
            put("omittedCount", omissions.size)
            put("omissionScope", "EXPLICIT_STAGE_OMISSIONS_ONLY")
            put("omissions", JsonObject(omitted.entries.take(MAX_DETAILS).associate { label(it.key) to JsonPrimitive(reason(it.value)) }))
            put("omissionDigest", digest(JsonObject(omitted.mapValues { JsonPrimitive(it.value) }).toString()))
            put("omissionReasons", JsonObject(reasons.entries.take(64).associate { it.key to JsonPrimitive(it.value) }))
            put("omissionReasonCount", reasons.size)
            put("detailsTruncated", (catalog?.size ?: 0) > MAX_DETAILS || (selected?.size ?: 0) > MAX_DETAILS || omitted.size > MAX_DETAILS || reasons.size > 64)
            put("maxDetails", MAX_DETAILS)
            put("executionEvidence", false)
        }
    }

    private fun label(value: String): String = if (safeLabel.matches(value)) value else "sha256:${digest(value)}"
    private fun reason(value: String): String = value.takeIf { it in omissionReasons } ?: "UNCLASSIFIED_OMISSION"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
