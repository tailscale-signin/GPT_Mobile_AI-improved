package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
        field("environment_profile")?.let { put("environment_profile", it) }
        (result["changed_path_count"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..20 }?.let { put("changed_path_count", it) }
        val cases = (result["case_results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(32).filter {
            val name = (it["name"] as? JsonPrimitive)?.contentOrNull
            !name.isNullOrBlank() &&
                name.length <= 512 &&
                it["status"] in setOf(JsonPrimitive("FAILED"), JsonPrimitive("PASSED"), JsonPrimitive("SKIPPED")) &&
                digest.matches((it["report_sha256"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        put("case_results", JsonArray(cases))
        field("started_at")?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { put("started_at", it) }
        field("finished_at")?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { put("finished_at", it) }
        put("request_id", request)
        put("source_run_id", runId)
        put("source_sha", result.getValue("head_sha"))
        for (name in listOf("profile_binding", "image", "policy_sha256", "patch_identity", "output_sha256", "state", "exit_code")) put(name, result.getValue(name))
    }
}

/** Native GitHub and Copilot observations identify the executor independently of the model. */
internal fun directCodingObservation(
    profile: PlatformV2,
    call: ProviderEvent.ToolCall,
    action: String,
    result: JsonObject?,
    execution: JsonObject?,
    observedSha: String?,
    sourceRunId: String
): JsonObject? {
    if (sourceRunId.isBlank() || action.isBlank() || execution == null) return null
    fun count(key: String) = (execution[key] as? JsonPrimitive)?.longOrNull ?: 0L
    val reads = count("read_requests")
    val writes = count("mutation_requests")
    if (reads <= 0 && writes <= 0) return null
    val copilot = action in setOf("copilot_create_task", "copilot_task_status")
    if (action == "copilot_create_task" && (writes <= 0 || count("accepted_mutations") <= 0)) return null
    if (copilot &&
        (
            result?.get("schema") != JsonPrimitive("github.copilot-task.v1") ||
                result["execution_owner"] != JsonPrimitive("GITHUB_CLOUD") ||
                result["orchestration_owner"] != JsonPrimitive("ANDROID_DIRECT") ||
                (result["task_id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
            )
    ) {
        return null
    }
    val pinned = listOf("expected_head_sha", "expected_base_sha", "commit_sha").firstNotNullOfOrNull { key ->
        (call.arguments[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
    }
    val source = observedSha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: pinned ?: return null
    return buildJsonObject {
        put("executor", if (copilot) "github_copilot" else "github_api")
        put("controller", "android")
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
        put("source_sha", source)
        put("source_binding", if (source == observedSha) "RETURNED" else "REQUESTED")
        put("source_run_id", sourceRunId)
        put("action", action)
        put("read_requests", reads)
        put("mutation_requests", writes)
        if (copilot) {
            put("task_id", requireNotNull(result).getValue("task_id"))
            result["status"]?.let { put("task_status", it) }
        }
    }
}

/** A scored Android-directed repair requires the same named test on two different tested candidates. */
internal fun pairedCodingRepairs(rows: List<JsonObject>): JsonArray {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun cases(row: JsonObject, status: String): Map<String, JsonObject> {
        val grouped = (row["case_results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().groupBy { it.text("name") }
        return buildMap {
            for ((name, matches) in grouped) {
                val result = matches.singleOrNull() ?: continue
                if (!name.isNullOrBlank() && result.text("status") == status && result.text("report_sha256") != null) put(name, result)
            }
        }
    }
    val keys = listOf("source_run_id", "profile_uid", "provider", "observed_model", "profile_binding", "repository", "branch", "source_sha", "policy_sha256", "image", "check_index")
    val observed = rows.takeLast(64).filter { row ->
        row.text("model_identity") == "MATCH" &&
            row.text("environment_profile") == "Android / Kotlin" &&
            listOf("receipt_id", "patch_identity", "request_id").all { !row.text(it).isNullOrBlank() }
    }
    val repairs = mutableListOf<JsonObject>()
    for (after in observed.filter { it.text("state") == "PASSED" }) {
        val passed = cases(after, "PASSED")
        val start = after.text("started_at")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: continue
        for (before in observed.filter { it.text("state") == "FAILED" }) {
            val finish = before.text("finished_at")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: continue
            if (finish > start ||
                before["patch_identity"] == after["patch_identity"] ||
                before["request_id"] == after["request_id"] ||
                keys.any { before[it] == null || before[it] != after[it] }
            ) {
                continue
            }
            for ((name, failure) in cases(before, "FAILED")) {
                val success = passed[name] ?: continue
                if (failure["report_sha256"] == success["report_sha256"]) continue
                repairs += buildJsonObject {
                    keys.forEach { put(it, after.getValue(it)) }
                    put("test", name)
                    put("before_receipt", before.getValue("receipt_id"))
                    put("after_receipt", after.getValue("receipt_id"))
                    put("failed_report_sha256", failure.getValue("report_sha256"))
                    put("passed_report_sha256", success.getValue("report_sha256"))
                    put("original_candidate", before.getValue("patch_identity"))
                    put("repaired_candidate", after.getValue("patch_identity"))
                }
                if (repairs.size == 32) return JsonArray(repairs)
            }
        }
    }
    return JsonArray(repairs)
}
