package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
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
