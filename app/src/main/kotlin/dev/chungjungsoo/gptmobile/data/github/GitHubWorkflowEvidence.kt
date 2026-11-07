package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bind CI evidence to the requested resource, accepting either supported ID encoding. */
internal object GitHubWorkflowEvidence {
    fun objectPayload(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject
        ?: throw GitHubReadValidation(
            "GITHUB_UNEXPECTED_RESPONSE",
            buildJsonObject {
                put("code", "GITHUB_UNEXPECTED_RESPONSE")
                put("message", "GitHub returned a non-object response for a workflow resource.")
            }
        )

    /** Definition timestamps describe only the captured page, never a run or build. */
    fun definitionPageEvidence(values: JsonArray, totalCount: Int?, page: Int, perPage: Int): JsonObject {
        val dated = values.mapNotNull { value ->
            val row = value as? JsonObject ?: return@mapNotNull null
            val timestamp = (row["updated_at"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            runCatching { java.time.Instant.parse(timestamp) }.getOrNull()?.let { it to row }
        }
        val latest = dated.maxOfOrNull { it.first }
        val complete = page == 1 && totalCount != null && totalCount >= 0 && totalCount <= perPage && values.size == totalCount
        return buildJsonObject {
            put("resource_type", "workflow_definitions")
            put("sort_field_for_latest", "updated_at")
            put("latest_scope", "PARSED_TIMESTAMPS_IN_CAPTURED_PAGE")
            put("timestamp_count", dated.size)
            put("unusable_timestamp_count", values.size - dated.size)
            put("collection_coverage_complete", complete)
            put("latest_global_established", complete && dated.size == values.size && latest != null)
            if (latest != null) {
                put("latest_observed_updated_at", latest.toString())
                put(
                    "latest_observed_workflows",
                    JsonArray(
                        dated.filter { it.first == latest }.map { (_, row) ->
                            JsonObject(row.filterKeys { it in setOf("id", "name", "path", "updated_at") })
                        }
                    )
                )
            }
            put("branch_head_verified", false)
            put("workflow_run_verified", false)
            put("build_status_verified", false)
        }
    }

    fun validateRun(run: JsonObject, arguments: JsonObject) = validate(run, arguments, "run_id", "workflow run")

    fun validateJob(job: JsonObject, arguments: JsonObject) {
        validate(job, arguments, "job_id", "workflow job")
        arguments["run_id"]?.let { requested ->
            if (id(job["run_id"]) != id(requested)) mismatch("The job belongs to another workflow run.", job, arguments)
        }
    }

    private fun validate(resource: JsonObject, arguments: JsonObject, idKey: String, kind: String) {
        val expected = id(arguments[idKey])
        if (expected == null || id(resource["id"]) != expected) mismatch("GitHub returned a different $kind.", resource, arguments)
        (arguments["expected_head_sha"] as? JsonPrimitive)?.content?.let { head ->
            val observed = (resource["head_sha"] as? JsonPrimitive)?.content
            if (!head.equals(observed, ignoreCase = true)) mismatch("The $kind belongs to another commit.", resource, arguments)
        }
    }

    private fun id(value: JsonElement?) = (value as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }

    private fun mismatch(message: String, observed: JsonObject, arguments: JsonObject): Nothing = throw GitHubReadValidation(
        "GITHUB_CI_RESOURCE_MISMATCH",
        buildJsonObject {
            put("code", "GITHUB_CI_RESOURCE_MISMATCH")
            put("message", "$message Refresh the exact run/job and its revision before consuming CI evidence.")
            put("requested", JsonObject(arguments.filterKeys { it in setOf("run_id", "job_id", "expected_head_sha") }))
            put("observed", JsonObject(observed.filterKeys { it in setOf("id", "run_id", "head_sha", "head_branch", "run_attempt") }))
        }
    )
}
