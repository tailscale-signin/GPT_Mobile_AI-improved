package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** File inventory stays complete for the page even when patch excerpts exceed the allowance. */
internal object GitHubCommitDetail {
    fun present(result: JsonObject, page: Int, perPage: Int, includePatches: Boolean): JsonObject {
        require(page in 1..1000 && perPage in 1..100)
        val sha = result["sha"] ?: JsonNull
        val files = result["files"]?.jsonArray.orEmpty()
        var patchBudget = 32 * 1024
        return buildJsonObject {
            put("sha", sha)
            put("commit_sha", sha)
            listOf("commit", "stats", "parents", "author", "committer", "html_url").forEach { key -> result[key]?.let { put(key, it) } }
            put(
                "files",
                JsonArray(
                    files.map { row ->
                        val item = row.jsonObject
                        buildJsonObject {
                            item.filterKeys { it in setOf("filename", "status", "additions", "deletions", "changes", "sha", "previous_filename") }.forEach { (key, value) -> put(key, value) }
                            if (includePatches) {
                                val patch = item["patch"]?.jsonPrimitive?.contentOrNull
                                val excerpt = patch?.let { GitHubRepositorySession.utf8Prefix(it, minOf(2000, patchBudget)) }
                                val returnedBytes = excerpt?.toByteArray()?.size ?: 0
                                patchBudget -= returnedBytes
                                excerpt?.let { put("patch", it) }
                                put("patch_truncated", patch != null && excerpt != patch)
                                put("patch_unavailable", patch == null)
                                put("patch_returned_bytes", returnedBytes)
                            }
                        }
                    }
                )
            )
            put("patches_included", includePatches)
            put("page", page)
            put("per_page", perPage)
            put("files_returned", files.size)
            put("has_more", files.size == perPage)
            put("files_truncated", files.size == perPage)
            put("source_guidance", "Use github_read ranges at commit_sha for complete current source. Removed files require an explicitly selected parent SHA. Patch excerpts are not complete diffs or replacement file contents.")
            if (files.size == perPage) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "get_commit_detail")
                        put("ref", sha)
                        put("commit_sha", sha)
                        put("page", page + 1)
                        put("per_page", perPage)
                        put("include_patches", includePatches)
                    }
                )
            }
        }
    }
}
