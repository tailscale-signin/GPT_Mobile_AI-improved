package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.captureProviderInvocation
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Search receipts identify source paths; their ev_ IDs are not native source-store handles. */
internal object GitHubSearchSourceRead {
    private val sha = Regex("[a-fA-F0-9]{40}")

    /** A bounded next read uses only paths actually returned at the search's immutable commit. */
    fun recipe(readTool: String, arguments: JsonObject, result: JsonObject): JsonObject? = recipe(readTool, arguments, result, useHitLines = true)

    private fun recipe(readTool: String, arguments: JsonObject, result: JsonObject, useHitLines: Boolean): JsonObject? {
        val owner = arguments.text("owner").takeIf(String::isNotBlank) ?: return null
        val repo = arguments.text("repo").takeIf(String::isNotBlank) ?: return null
        val source = GitHubSemanticSummary.data(result)
        val commit = source.text("commit_sha").takeIf(sha::matches) ?: return null
        if ("commit_sha" in arguments && !arguments.text("commit_sha").equals(commit, true)) return null
        val paths = observedPaths(source) ?: return null
        if (paths.isEmpty()) return null
        val rangesByPath = paths.entries.take(8).map { (path, observation) ->
            val starts = mutableListOf<Int>()
            if (useHitLines) {
                for (line in observation.lines.sorted()) {
                    if (starts.isEmpty() || line.toLong() - starts.last() >= MATCH_WINDOW_LINES) starts += line
                }
            }
            val count = if (starts.isEmpty()) 200 else MATCH_WINDOW_LINES
            starts.ifEmpty { listOf(1) }.map { start ->
                buildJsonObject {
                    put("path", path)
                    put("start_line", start)
                    put("max_lines", count)
                }
            }
        }
        // Cover one observation per file before assigning spare slots to distant hits.
        val ranges = rangesByPath.map { it.first() } + rangesByPath.flatMap { it.drop(1) }
        return buildJsonObject {
            put("tool", readTool)
            put(
                "arguments",
                buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                    put("commit_sha", commit)
                    put("ranges", JsonArray(ranges.take(8)))
                }
            )
        }
    }

    /** Follow the native selector only if its content, revision, repository and active tool still agree. */
    fun next(
        id: String,
        receipt: JsonObject,
        binding: String,
        tools: List<AgentToolDefinition>,
        authorized: List<AgentToolDefinition>,
        receipts: Collection<JsonObject>
    ): ProviderEvent.ToolCall? {
        if (receipt.text("receipt_id") != id ||
            receipt.text("binding") != binding ||
            receipt["trusted"] != JsonPrimitive(true) ||
            receipt["success"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] != JsonPrimitive(true)
        ) {
            return null
        }
        val issuer = receipt.text("tool")
        if (issuer.substringBefore("__") != "github_search") return null
        val readName = issuer.replaceFirst("github_search", "github_read")
        val source = receipt["result"] as? JsonObject ?: return null
        if (receipt.text("source_result_sha256") != GitHubRepositoryStateStore.digest(source.toString())) return null
        val data = GitHubSemanticSummary.data(source)
        val view = receipt["view"] as? JsonObject
        val issued = (data["source_read_call"] ?: source["source_read_call"] ?: view?.get("source_read_call")) as? JsonObject ?: return null
        val args = issued["arguments"] as? JsonObject ?: return null
        val original = receipt["arguments"] as? JsonObject ?: return null
        val owner = args.text("owner")
        val repo = args.text("repo")
        if (!"$owner/$repo".equals(receipt.text("repository"), true) ||
            receipt.text("source_head_sha") != args.text("commit_sha") ||
            listOf("owner", "repo").any { it in original && !original.text(it).equals(args.text(it), true) }
        ) {
            return null
        }
        val bound = JsonObject(original + mapOf("owner" to JsonPrimitive(owner), "repo" to JsonPrimitive(repo)))
        // Previously saved search receipts issued a first-page read even for a line hit.
        if (issued != recipe(readName, bound, source) && issued != recipe(readName, bound, source, useHitLines = false)) return null
        if (GitHubOperationArguments.errors("github_read", args).isNotEmpty()) return null
        val reader = tools.singleOrNull { it.name == readName && it.modelVisible }
        val definition = reader ?: authorized.singleOrNull { it.name == readName && it.modelVisible } ?: return null
        if (ToolArgumentValidator.errors(args, definition.inputSchema).isNotEmpty()) return null
        val fingerprint = GitHubEvidenceBook.digest("$binding|$readName|${args.toSortedMap()}").take(24)
        val readId = "pinned-source-$fingerprint"
        if (receipts.any { it["call_id"] == JsonPrimitive(readId) || it["tool"] == JsonPrimitive(readName) && it["arguments"] == args }) return null
        if (reader != null) return ProviderEvent.ToolCall(readId, readName, args)
        val discovery = tools.singleOrNull { it.name == GitHubAuthorizedCatalog.TOOL && it.modelVisible } ?: return null
        val discoverId = "pinned-source-schema-$fingerprint"
        val names = buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(readName)))) }
        if (receipts.any { it["call_id"] == JsonPrimitive(discoverId) } || ToolArgumentValidator.errors(names, discovery.inputSchema).isNotEmpty()) return null
        return ProviderEvent.ToolCall(discoverId, discovery.name, names)
    }

    /** Caller supplies the bound, revalidated original from the encrypted evidence book. */
    fun repair(call: ProviderEvent.ToolCall, receipt: JsonObject, binding: String): ProviderEvent.ToolCall? {
        if (call.name.githubOperationFamily() != "github_read" || "view_cursor" in call.arguments) return null
        val original = runCatching { call.captureProviderInvocation() }.getOrNull() ?: return null
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
        return original.copy(arguments = corrected)
    }

    private const val MATCH_WINDOW_LINES = 32
    private data class SearchFile(val blob: String, val lines: MutableSet<Int> = linkedSetOf())

    private fun observedPaths(source: JsonObject): Map<String, SearchFile>? {
        val items = source["items"] as? JsonArray ?: return null
        val paths = linkedMapOf<String, SearchFile>()
        for (item in items) {
            val row = item as? JsonObject ?: continue
            val path = row.text("path")
            val blob = row.text("sha")
            if (!sha.matches(blob) || path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path || path.split('/').any { it in setOf("", ".", "..") }) continue
            val file = paths.getOrPut(path) { SearchFile(blob) }
            if (file.blob != blob) return null
            for (match in (row["matches"] as? JsonArray).orEmpty().take(10)) {
                val line = ((match as? JsonObject)?.get("line") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (line != null && line > 0 && file.lines.size < 10) file.lines += line
            }
        }
        return paths
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
