package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Join the receipt uploaded in this job's own log, including the checkout actually tested by PR validation. */
internal object GitHubCiTestEvidence {
    const val ENTRY = "ci-junit.json"
    private val sha = Regex("[a-fA-F0-9]{40}")
    private val digest = Regex("[a-f0-9]{64}")
    private val upload = Regex("""Artifact (ci-junit-(\d+)-(\d+)-([-A-Za-z0-9_]+)) has been successfully uploaded! Final size is \d+ bytes\. Artifact ID is (\d+)\s*$""")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    suspend fun read(
        lines: List<String>,
        identity: JsonObject,
        changed: Set<String>,
        maxArchiveBytes: Int,
        metadata: suspend (Long) -> JsonObject,
        archive: suspend (Long, String) -> String,
        redact: (String) -> String
    ): Pair<String, List<JsonObject>> {
        val marker = lines.mapNotNull { upload.find(it)?.groupValues }.distinct().singleOrNull()
            ?: return "NO_BOUND_JUNIT_UPLOAD" to emptyList()
        if (marker[2] != identity.text("run_id") || marker[3] != identity.text("run_attempt")) return "ATTEMPT_MISMATCH" to emptyList()
        val id = marker[5].toLongOrNull()?.takeIf { it > 0 } ?: return "INVALID_ARTIFACT_ID" to emptyList()
        val artifact = metadata(id)
        val run = artifact["workflow_run"] as? JsonObject
        val hash = artifact.text("digest").removePrefix("sha256:")
        if (artifact.text("id") != id.toString() ||
            artifact.text("name") != marker[1] ||
            artifact["expired"] != JsonPrimitive(false) ||
            ((artifact["size_in_bytes"] as? JsonPrimitive)?.longOrNull ?: 0L) !in 1L..maxArchiveBytes.toLong() ||
            run?.text("id") != marker[2] ||
            !run.text("head_sha").equals(identity.text("head_sha"), true) ||
            !digest.matches(hash)
        ) {
            return "ARTIFACT_IDENTITY_UNAVAILABLE" to emptyList()
        }
        val doc = Json.parseToJsonElement(archive(id, hash)) as? JsonObject ?: return "INVALID_JUNIT_RECEIPT" to emptyList()
        val tested = doc.text("tested_sha")
        val cases = doc["failures"] as? JsonArray
        if (doc["schema_version"] != JsonPrimitive(1) ||
            !doc.text("repository").equals(identity.text("repository"), true) ||
            doc.text("run_id") != marker[2] ||
            doc.text("run_attempt") != marker[3] ||
            doc.text("job") != marker[4] ||
            !doc.text("head_sha").equals(identity.text("head_sha"), true) ||
            !sha.matches(tested) ||
            cases == null ||
            cases.size > 30
        ) {
            return "JUNIT_IDENTITY_MISMATCH" to emptyList()
        }
        if (doc["tracked_files_clean"] != JsonPrimitive(true)) return "JUNIT_CHECKOUT_UNVERIFIED" to emptyList()
        val evidence = cases.map { entry ->
            val case = entry as? JsonObject ?: return "INVALID_JUNIT_RECEIPT" to emptyList()
            val owner = case.text("class_name")
            val name = case.text("name")
            val xmlHash = case.text("report_sha256")
            if (owner.length !in 1..500 ||
                name.length !in 1..1000 ||
                !digest.matches(xmlHash) ||
                case.text("outcome") !in setOf("ERROR", "FAILURE")
            ) {
                return "INVALID_JUNIT_RECEIPT" to emptyList()
            }
            val path = case.text("source_path").takeIf(GitHubCiDiagnosis::repositoryPath)
            buildJsonObject {
                identity.forEach { (field, value) -> put(field, value) }
                put("source", "JUNIT_ARTIFACT")
                put("tested_sha", tested)
                put("artifact_id", id)
                put("artifact_digest", hash)
                put("report_sha256", xmlHash)
                put("test_class", redact(owner))
                put("test_name", redact(name))
                put("outcome", case.text("outcome"))
                put("confidence", "TEST_FAILURE_OBSERVED")
                put("location_status", if (path == null) "TEST_CASE_ONLY" else "TEST_CLASS_SOURCE")
                path?.let { put("source_path", it) }
                put("matching_changed_paths", JsonArray(listOfNotNull(path?.takeIf { tested.equals(identity.text("head_sha"), true) && it in changed }).map(::JsonPrimitive)))
            }
        }
        return (if (doc["partial"] == JsonPrimitive(true)) "PARTIAL_JUNIT_RECEIPT" else "JUNIT_RECEIPT_READ") to evidence
    }
}
