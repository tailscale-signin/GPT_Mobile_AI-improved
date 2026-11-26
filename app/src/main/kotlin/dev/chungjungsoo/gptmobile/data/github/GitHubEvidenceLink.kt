package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A Git object hash is not a commit unless its observed field identifies a commit. */
internal object GitHubEvidenceLink {
    private val commitFields = setOf("commit_sha", "head_sha", "base_sha", "merge_commit_sha", "source_head_sha", "expected_head_sha")
    private val branchFields = setOf("branch", "ref", "head_ref", "base_ref", "head_branch", "base_branch", "default_branch")
    private val sha = Regex("[a-fA-F0-9]{40}")

    fun url(base: String, pointer: String, value: String, receipt: JsonObject): String? {
        val field = pointer.substringAfterLast('/')
        val parentPointer = pointer.substringBeforeLast('/', "")
        val result = receipt["result"] as? JsonObject ?: return null
        val parent = at(result, parentPointer) as? JsonObject
        val tool = receipt.text("tool").githubOperationFamily()
        val action = (receipt["arguments"] as? JsonObject)?.text("action").orEmpty()
        val file = parent?.takeIf { it.text("path").isNotBlank() || it.text("filename").isNotBlank() }
        if (tool == "github_ci" && action == "failure" && parent != null) {
            when (field) {
                "source_path", "test_name" -> {
                    val revision = when (parent.text("source")) {
                        "JUNIT_ARTIFACT" -> parent.text("tested_sha")
                        "CHECK_RUN_ANNOTATION" -> parent.text("check_head_sha")
                        else -> ""
                    }
                    return sourceUrl(base, parent.text("source_path"), revision, (parent["start_line"] as? JsonPrimitive)?.intOrNull, (parent["end_line"] as? JsonPrimitive)?.intOrNull)
                }
                "job_id" -> {
                    val runId = parent.text("run_id").toLongOrNull()?.takeIf { it > 0 } ?: return null
                    return value.toLongOrNull()?.takeIf { it > 0 }?.let { "$base/actions/runs/$runId/job/$it" }
                }
                "tested_sha", "check_head_sha" -> return value.takeIf(sha::matches)?.let { "$base/commit/$it" }
            }
        }
        return when {
            field in branchFields && value.isNotBlank() -> "$base/tree/${encode(value)}"
            field in commitFields && sha.matches(value) -> "$base/commit/$value"
            field == "sha" && parentPointer.substringAfterLast('/') in setOf("head", "base", "commit") && sha.matches(value) -> "$base/commit/$value"
            file != null && field in setOf("path", "filename", "sha", "blob_sha", "expected_blob_sha") -> fileUrl(base, file, result, receipt)
            field == "sha" &&
                tool == "github_context" &&
                action in setOf("commit", "commits", "resolve") &&
                (parent === result || parentPointer.matches(Regex("(?:/data)?/(?:items|recent_commits|parents)/[0-9]+"))) &&
                sha.matches(value) -> "$base/commit/$value"
            field == "number" && tool == "github_pr" -> value.toLongOrNull()?.takeIf { it > 0 }?.let { "$base/pull/$it" }
            field == "number" && tool == "github_issue" -> value.toLongOrNull()?.takeIf { it > 0 }?.let { "$base/issues/$it" }
            else -> null
        }
    }

    private fun fileUrl(base: String, file: JsonObject, result: JsonObject, receipt: JsonObject): String? {
        val path = file.text("path").ifBlank { file.text("filename") }
        val data = (result["data"] as? JsonObject) ?: result
        val commit = sequenceOf(file.text("commit_sha"), data.text("commit_sha"), data.text("head_sha"), (receipt["arguments"] as? JsonObject)?.text("commit_sha").orEmpty())
            .firstOrNull { sha.matches(it) } ?: return null
        val start = (file["start_line"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        val end = (file["end_line"] as? JsonPrimitive)?.intOrNull?.takeIf { start != null && it >= start }
        return sourceUrl(base, path, commit, start, end)
    }

    /** The origin is recorded by the admitted native tool, independently of the remote response. */
    fun recordedRepositoryUrl(receipt: JsonObject): String? {
        val origin = receipt.text("web_origin").takeIf(String::isNotBlank) ?: return null
        if (runCatching { GitHubApiOrigins.fromEndpoint(origin).authorization }.getOrNull() != origin) return null
        val repository = receipt.text("repository").split('/')
        if (repository.size != 2 || repository.any { !it.matches(Regex("[A-Za-z0-9_.-]+")) || it in setOf(".", "..") }) return null
        return "$origin/${repository.joinToString("/")}"
    }

    fun excerptUrl(receipt: JsonObject, path: String, binding: JsonObject): String? {
        val base = recordedRepositoryUrl(receipt) ?: return null
        val start = (binding["start_line"] as? JsonPrimitive)?.intOrNull ?: return null
        val end = (binding["end_line"] as? JsonPrimitive)?.intOrNull ?: return null
        if (start < 1 || end < start) return null
        return sourceUrl(base, path, binding.text("commit_sha"), start, end)
    }

    fun sourceUrl(base: String, path: String, commit: String, start: Int?, end: Int?): String? {
        if (!sha.matches(commit) || path.isBlank() || path.split('/').any { it in setOf("", ".", "..") } || path.any { it.code < 32 || it == '\\' }) return null
        val lines = if (start == null) {
            ""
        } else if (end == null || end == start) {
            "#L$start"
        } else {
            "#L$start-L$end"
        }
        return "$base/blob/$commit/${encode(path)}$lines"
    }

    private fun at(root: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) return root
        var node = root
        for (part in pointer.removePrefix("/").split('/')) {
            val key = part.replace("~1", "/").replace("~0", "~")
            node = when (val current = node) {
                is JsonObject -> current[key]
                is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return node
    }

    private fun encode(value: String): String = value.split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
