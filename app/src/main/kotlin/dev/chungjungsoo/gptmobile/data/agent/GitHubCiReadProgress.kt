package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** CI progress follows repository/run/attempt/job identities and covered lines, not response hashes. */
internal class GitHubCiReadProgress {
    private val observations = linkedSetOf<String>()
    private val ranges = linkedMapOf<String, MutableList<IntRange>>()

    fun observe(call: ProviderEvent.ToolCall, result: AgentToolResult): Boolean? {
        if (call.name.githubOperationFamily() != "github_ci" || "view_cursor" in call.arguments) return null
        if (result.isError || result.sharedResult) return false
        val data = data(result) ?: return false
        val scope = scope(call, result) ?: return false
        var advanced = false
        fun cover(key: String, window: IntRange) {
            val covered = ranges.getOrPut(key) { mutableListOf() }
            if (covered.any { window.first >= it.first && window.last <= it.last }) return
            val touching = covered.filter { it.first.toLong() <= window.last.toLong() + 1 && window.first.toLong() <= it.last.toLong() + 1 }
            covered.removeAll(touching.toSet())
            covered += minOf(window.first, touching.minOfOrNull { it.first } ?: window.first)..maxOf(window.last, touching.maxOfOrNull { it.last } ?: window.last)
            advanced = true
        }
        fun record(row: JsonObject, kind: String) {
            val id = row["id"] ?: row["job_id"] ?: row["run_id"] ?: return
            val head = row["head_sha"] ?: data["head_sha"] ?: call.arguments["head_sha"] ?: call.arguments["commit_sha"]
            val attempt = row["run_attempt"] ?: data["run_attempt"] ?: call.arguments["run_attempt"]
            val key = "$scope|$kind|$head|$id|$attempt|${row["status"]}|${row["conclusion"]}"
            if (observations.add(key)) advanced = true
        }
        when (call.arguments.text("action")) {
            "run", "job" -> record(data, call.arguments.text("action"))
            "runs", "jobs", "workflows" -> {
                val field = when (call.arguments.text("action")) {
                    "runs" -> "workflow_runs"
                    "jobs" -> "jobs"
                    else -> "workflows"
                }
                val rows = data[field] as? JsonArray ?: return false
                rows.filterIsInstance<JsonObject>().forEach { record(it, field) }
                if (rows.isEmpty() && data["has_more"] == JsonPrimitive(false)) {
                    val query = call.arguments.filterKeys { it !in setOf("response_detail", "verify_live", "per_page") }
                    if (observations.add("$scope|empty|$query")) advanced = true
                }
            }
            "logs", "failure" -> {
                val identity = logIdentity(data) ?: return false
                completeLines(data)?.let { cover("$scope|$identity", it) }
                // A long line can require several bounded pages. Newly read offsets
                // are progress without pretending that the whole line was delivered.
                val continuation = data["continuation"] as? JsonObject
                (data["excerpts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { row ->
                    val line = row.integer("line")?.takeIf { it > 0 } ?: return@forEach
                    val offset = row.integer("start_offset")?.takeIf { it >= 0 } ?: return@forEach
                    val text = (row["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                    val partial = offset > 0 || continuation?.get("start_line") == row["line"] && continuation?.integer("start_offset")?.let { it > 0 } == true
                    val end = offset.toLong() + text.length - 1
                    if (partial && text.isNotEmpty() && end <= Int.MAX_VALUE) cover("$scope|$identity|line:$line", offset..end.toInt())
                }
            }
            else -> return null
        }
        while (observations.size > 1024) observations.remove(observations.first())
        while (ranges.size > 128) ranges.remove(ranges.keys.first())
        return advanced
    }

    companion object {
        /** Reuse only explicitly pinned completed attempts; live refreshes keep their normal dispatch. */
        fun reused(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>): AgentToolResult? {
            val args = call.arguments
            if (call.name.githubOperationFamily() != "github_ci" ||
                args["verify_live"] == JsonPrimitive(true) ||
                args.keys.any { it !in reusableArguments } ||
                args.integer("run_attempt")?.let { it > 0 } != true
            ) {
                return null
            }
            val head = args.text("expected_head_sha").ifBlank { args.text("head_sha") }.ifBlank { args.text("commit_sha") }
            if (!head.matches(sha) || args.text("owner").isBlank() || args.text("repo").isBlank()) return null
            val action = args.text("action")
            if (action !in setOf("run", "jobs", "logs")) return null
            val requestedLines = if (action == "logs") {
                val start = args.integer("start_line")?.takeIf { it > 0 } ?: return null
                val end = args.integer("end_line")?.takeIf { it >= start } ?: return null
                start..end
            } else {
                null
            }
            val previous = exchanges.asReversed().firstNotNullOfOrNull { exchange ->
                exchange.calls.asReversed().firstNotNullOfOrNull previousCall@{ old ->
                    if (old.name != call.name || old.arguments.text("action") != action) return@previousCall null
                    val result = exchange.results.singleOrNull { it.callId == old.callId } ?: return@previousCall null
                    if (result.isError || result.sharedResult || result.errorCode != null) return@previousCall null
                    val source = data(result) ?: return@previousCall null
                    val resource = result.executionReceipt?.get("resource_context") as? JsonObject
                    if (listOf("owner", "repo").any { key ->
                            !args.text(key).equals(old.arguments.text(key).ifBlank { resource?.text(key).orEmpty() }, true)
                        } ||
                        source.text("head_sha") != head ||
                        source["run_attempt"] != args["run_attempt"]
                    ) {
                        return@previousCall null
                    }
                    val run = if (action == "run") source["id"] else source["run_id"]
                    if (args["run_id"] != null && args["run_id"] != run || args["run_id"] == null && action != "logs") return@previousCall null
                    val complete = when (action) {
                        "run" -> source.text("status") == "completed" && source.text("conclusion").isNotBlank()
                        "jobs" -> {
                            val jobs = source["jobs"] as? JsonArray
                            source["has_more"] == JsonPrimitive(false) &&
                                source["complete"] != JsonPrimitive(false) &&
                                source["pagination_inconsistent"] != JsonPrimitive(true) &&
                                source.integer("page") == 1 &&
                                (args.integer("page") ?: 1) == 1 &&
                                jobs != null &&
                                source.integer("returned") == jobs.size &&
                                source.integer("total_count") == jobs.size &&
                                jobs.all { (it as? JsonObject)?.text("status") == "completed" }
                        }
                        else -> source["job_id"] == args["job_id"] &&
                            source.text("job_status") == "completed" &&
                            completeLines(source)?.let { window -> requireNotNull(requestedLines).first >= window.first && requestedLines.last <= window.last } == true
                    }
                    if (complete) result else null
                }
            } ?: return null
            return currentGitHubInvocationReceipt(
                AgentToolResult(
                    call.callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("schema", "github.repeat.v1")
                            put("operation", "github_ci")
                            put("status", "OBSERVATION_REUSED")
                            put("previous_call_id", previous.callId)
                            put("message", "The completed pinned attempt already supplies this observation. Use its saved receipt; no remote read was repeated.")
                            put("head_sha", head)
                            put("run_attempt", requireNotNull(args["run_attempt"]))
                            requestedLines?.let {
                                put("covered_start_line", it.first)
                                put("covered_end_line", it.last)
                            }
                        }
                    ),
                    isError = false,
                    dispatched = false,
                    sharedResult = true,
                    executionReceipt = buildJsonObject { put("source_observation_call_id", previous.callId) }
                )
            )
        }

        private fun data(result: AgentToolResult): JsonObject? {
            val raw = ToolResultEnvelope.element(result.retainedContent ?: result.content) as? JsonObject ?: return null
            if (raw["schema"] == JsonPrimitive("github.repeat.v1") || raw["observationTruncated"] == JsonPrimitive(true)) return null
            return GitHubSemanticSummary.data(raw)
        }

        private fun scope(call: ProviderEvent.ToolCall, result: AgentToolResult): String? {
            val resource = result.executionReceipt?.get("resource_context") as? JsonObject
            val owner = call.arguments.text("owner").ifBlank { resource?.text("owner").orEmpty() }
            val repository = call.arguments.text("repo").ifBlank { resource?.text("repo").orEmpty() }
            if (owner.isBlank() || repository.isBlank()) return null
            return "${call.name.substringAfter("__", "")}|${owner.lowercase(java.util.Locale.ROOT)}/${repository.lowercase(java.util.Locale.ROOT)}"
        }

        private fun logIdentity(data: JsonObject): String? {
            if (!data.text("head_sha").matches(sha) ||
                data.integer("run_attempt")?.let { it > 0 } != true ||
                listOf("run_id", "job_id").any { (data[it] as? JsonPrimitive)?.longOrNull?.let { value -> value > 0 } != true }
            ) {
                return null
            }
            return "${data["head_sha"]}|${data["run_id"]}|${data["run_attempt"]}|${data["job_id"]}"
        }

        private fun completeLines(data: JsonObject): IntRange? {
            if (data["truncated"] == JsonPrimitive(true) || data["log_complete"] != JsonPrimitive(true)) return null
            val returned = data["excerpts"] as? JsonArray ?: return null
            val continuation = data["continuation"] as? JsonObject
            if (data["has_more"] !in setOf(JsonPrimitive(true), JsonPrimitive(false))) return null
            if (data["has_more"] == JsonPrimitive(true) && continuation == null) return null
            val rows = returned.filter { value ->
                val row = value as? JsonObject ?: return null
                row.integer("start_offset").let { it == null || it == 0 } &&
                    !(continuation?.get("start_line") == row["line"] && continuation?.integer("start_offset")?.let { it > 0 } == true)
            }
            if (rows.isEmpty()) return null
            val lines = rows.map { value ->
                val row = value as? JsonObject ?: return null
                if (row["text"] !is JsonPrimitive || row.integer("start_offset").let { it != null && it != 0 }) return null
                row.integer("line")?.takeIf { it > 0 } ?: return null
            }
            if (lines.zipWithNext().any { (a, b) -> b.toLong() != a.toLong() + 1 }) return null
            return lines.first()..lines.last()
        }

        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
        private val sha = Regex("[a-fA-F0-9]{40}")
        private val reusableArguments = setOf("action", "owner", "repo", "run_id", "run_attempt", "job_id", "expected_head_sha", "head_sha", "commit_sha", "start_line", "end_line", "page", "per_page", "response_detail", "verify_live")
    }
}
