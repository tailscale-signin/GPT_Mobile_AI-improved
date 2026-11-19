package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** An observed paired check covers one source and candidate on one route. */
internal fun workspaceCheckObservation(
    profile: PlatformV2?,
    call: ProviderEvent.ToolCall,
    result: JsonObject?,
    repository: String,
    branch: String,
    runId: String?
): JsonObject? {
    if (profile == null || result == null || runId.isNullOrBlank()) return null
    fun field(key: String): String? = (result[key] as? JsonPrimitive)?.contentOrNull
    val action = (call.arguments["action"] as? JsonPrimitive)?.contentOrNull
    val request = (call.arguments["request_id"] as? JsonPrimitive)?.contentOrNull
    if (action !in setOf("run", "status") || request == null || request != field("request_id")) return null
    if (field("executor") != "gateway" || field("scope") != "exploratory" || field("repository") != repository || field("branch") != branch) return null
    val digest = Regex("[a-f0-9]{64}")
    if (listOf("profile_binding", "patch_identity", "policy_sha256", "output_sha256").any { !digest.matches(field(it).orEmpty()) }) return null
    if (!Regex("[a-f0-9]{40}").matches(field("head_sha").orEmpty()) || !Regex("(?:[^\\s@]+@)?sha256:[a-f0-9]{64}").matches(field("image").orEmpty())) return null
    val exit = (result["exit_code"] as? JsonPrimitive)?.intOrNull ?: return null
    val index = (result["check_index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
    val state = field("state")
    if (state != (if (exit == 0) "PASSED" else "FAILED")) return null
    return buildJsonObject {
        put("profile_uid", profile.uid)
        put("provider", profile.compatibleType.name)
        put("requested_model", profile.model)
        call.reportedModelId?.let { put("observed_model", it) }
        put(
            "model_identity",
            when (call.reportedModelId) {
                null -> "UNOBSERVED"
                profile.model -> "MATCH"
                else -> "DIFFERENT"
            }
        )
        put("executor", "gateway")
        put("repository", repository)
        put("branch", branch)
        put("check_index", index)
        val started = (result["started_at"] as? JsonPrimitive)?.doubleOrNull
        val finished = (result["finished_at"] as? JsonPrimitive)?.doubleOrNull
        if (started != null && finished != null && (finished - started).isFinite() && finished >= started) put("elapsed_seconds", finished - started)
        put("request_id", request)
        put("source_run_id", runId)
        put("source_sha", result.getValue("head_sha"))
        for (name in listOf("profile_binding", "image", "policy_sha256", "patch_identity", "output_sha256", "state", "exit_code")) put(name, result.getValue(name))
    }
}
