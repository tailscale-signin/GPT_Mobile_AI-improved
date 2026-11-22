package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.captureProviderInvocation
import dev.chungjungsoo.gptmobile.data.agent.providerInvocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Search receipts identify source paths; their ev_ IDs are not native source-store handles. */
internal object GitHubSearchSourceRead {
    private val sha = Regex("[a-fA-F0-9]{40}")

    /** A bounded next read uses only paths actually returned at the search's immutable commit. */
    fun recipe(readTool: String, arguments: JsonObject, result: JsonObject): JsonObject? {
        val owner = arguments.text("owner").takeIf(String::isNotBlank) ?: return null
        val repo = arguments.text("repo").takeIf(String::isNotBlank) ?: return null
        val source = GitHubSemanticSummary.data(result)
        val commit = source.text("commit_sha").takeIf(sha::matches) ?: return null
        if ("commit_sha" in arguments && !arguments.text("commit_sha").equals(commit, true)) return null
        val paths = observedPaths(source) ?: return null
        if (paths.isEmpty()) return null
        return buildJsonObject {
            put("tool", readTool)
            put(
                "arguments",
                buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                    put("commit_sha", commit)
                    put(
                        "ranges",
                        JsonArray(
                            paths.keys.take(8).map { path ->
                                buildJsonObject {
                                    put("path", path)
                                    put("start_line", 1)
                                    put("max_lines", 200)
                                }
                            }
                        )
                    )
                }
            )
        }
    }

    /** Caller supplies the bound, revalidated original from the encrypted evidence book. */
    fun repair(call: ProviderEvent.ToolCall, receipt: JsonObject, binding: String): ProviderEvent.ToolCall? {
        if (call.name.githubOperationFamily() != "github_read" || "view_cursor" in call.arguments) return null
        if (runCatching { call.providerInvocation() }.isFailure) return null
        val id = call.arguments.text("evidence_id")
        if (!id.matches(Regex("ev_[a-f0-9]{24}")) ||
            receipt["receipt_id"] != JsonPrimitive(id) ||
            receipt["binding"] != JsonPrimitive(binding) ||
            receipt["trusted"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] != JsonPrimitive(true) ||
            receipt["success"] != JsonPrimitive(true)
        ) {
            return null
        }
        val sourceTool = receipt.text("tool")
        if (sourceTool.githubOperationFamily() != "github_search" ||
            sourceTool.removePrefix("github_search") != call.name.removePrefix("github_read")
        ) {
            return null
        }
        val result = receipt["result"] as? JsonObject ?: return null
        if (receipt["source_result_sha256"] != JsonPrimitive(GitHubRepositoryStateStore.digest(result.toString()))) return null
        val source = GitHubSemanticSummary.data(result)
        val commit = source.text("commit_sha").takeIf(sha::matches) ?: return null
        if (receipt["source_head_sha"] != JsonPrimitive(commit)) return null
        val repository = receipt.text("repository").split('/')
        if (repository.size != 2 || repository.any(String::isBlank)) return null
        val owner = call.arguments["owner"]
        val repo = call.arguments["repo"]
        if ((owner == null) != (repo == null)) return null
        if (owner != null && (!call.arguments.text("owner").equals(repository[0], true) || !call.arguments.text("repo").equals(repository[1], true))) return null
        if ("commit_sha" in call.arguments && !call.arguments.text("commit_sha").equals(commit, true)) return null
        val originalArguments = receipt["arguments"] as? JsonObject ?: return null
        if ("commit_sha" in originalArguments && !originalArguments.text("commit_sha").equals(commit, true)) return null
        for (key in listOf("ref", "branch")) {
            if (key in call.arguments && call.arguments[key] != originalArguments[key] && call.arguments[key] != JsonPrimitive(commit)) return null
        }
        val paths = observedPaths(source) ?: return null
        val ranges = call.arguments["ranges"] as? JsonArray ?: return null
        if (ranges.size !in 1..8 ||
            ranges.any { range ->
                val path = (range as? JsonObject)?.text("path")
                path == null || path !in paths
            }
        ) {
            return null
        }
        val corrected = JsonObject(
            call.arguments - "evidence_id" + buildJsonObject {
                put("owner", repository[0])
                put("repo", repository[1])
                put("commit_sha", commit)
            }
        )
        val original = if (call.originalInvocation == null && call.originalInvocationFingerprint == null) call.captureProviderInvocation() else call
        return original.copy(arguments = corrected)
    }

    private fun observedPaths(source: JsonObject): Map<String, String>? {
        val items = source["items"] as? JsonArray ?: return null
        val paths = linkedMapOf<String, String>()
        for (item in items) {
            val row = item as? JsonObject ?: continue
            val path = row.text("path")
            val blob = row.text("sha")
            if (!sha.matches(blob) || path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path || path.split('/').any { it in setOf("", ".", "..") }) continue
            val previous = paths.put(path, blob)
            if (previous != null && previous != blob) return null
        }
        return paths
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
