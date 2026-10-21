package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authorized local views preserve every returned record before following a remote continuation. */
internal class GitHubResultViews(
    private val now: () -> Long = System::currentTimeMillis,
    private val pageBytes: Int = 8 * 1024,
    private val capacityBytes: Int = 4 * 1024 * 1024
) {
    private data class Entry(val scope: String, val operation: String, val fragments: List<JsonObject>, val metadata: JsonObject, val remoteContinuation: JsonElement?, val expires: Long, val bytes: Int, val originalError: Boolean)
    private val entries = LinkedHashMap<String, Entry>()
    private var retainedBytes = 0

    @Synchronized fun present(scope: String, operation: String, modelName: String, upstream: AgentToolResult): AgentToolResult {
        val value = when (val content = upstream.content) {
            is ToolResultContent.Json -> content.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrNull() ?: JsonPrimitive(content.text)
            is ToolResultContent.ResourceLinks -> return upstream
        }
        val bytes = value.toString().toByteArray().size
        if (bytes <= pageBytes - 1024) return upstream.copy(content = ToolResultContent.Json(buildJsonObject {
            put("schema", SCHEMA); put("operation", operation); put("local_page_complete", true); put("data", value)
        }))
        purge()
        if (bytes > MAX_SOURCE_BYTES) return overflow(upstream, operation, value)
        val root = value as? JsonObject
        val remote = root?.get("continuation")
        val metadata = JsonObject(root.orEmpty().filter { (key, item) -> key in METADATA && item.toString().toByteArray().size < 1024 })
        val body = if (root != null) JsonObject(root - "continuation") else value
        val fragments = mutableListOf<JsonObject>()
        fragment("", body, fragments)
        // Only fragments and compact metadata are retained, not a second copy of the source.
        // Account for UTF-16 objects and collection overhead, not just wire bytes.
        val retained = fragments.sumOf { it.toString().toByteArray().size * 2L + 256 } + metadata.toString().toByteArray().size * 2L + (remote?.toString()?.toByteArray()?.size ?: 0) * 2L + 1024
        if (retained > capacityBytes) return overflow(upstream, operation, value)
        while (entries.isNotEmpty() && (entries.size >= 32 || retainedBytes + retained > capacityBytes)) evict(entries.keys.first())
        val id = UUID.randomUUID().toString()
        val entry = Entry(scope, operation, fragments, metadata, remote, now() + 10 * 60_000, retained.toInt(), upstream.isError)
        entries[id] = entry
        retainedBytes += entry.bytes
        return upstream.copy(content = ToolResultContent.Json(page(id, entry, 0, modelName)), retainedContent = upstream.retainedContent ?: upstream.content)
    }

    @Synchronized fun read(scope: String, operation: String, modelName: String, cursor: String, callId: String): AgentToolResult {
        purge()
        val parts = cursor.split(':')
        val entry = parts.firstOrNull()?.let(entries::get)
        val offset = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || entry == null || offset == null || entry.scope != scope || entry.operation != operation || offset !in entry.fragments.indices) {
            return AgentToolResult(callId, ToolResultContent.Text("This saved GitHub view is unavailable or outside the current authorized task. Reconcile any prior mutation using its receipt; do not repeat the mutation to recover output."), true, errorCode = "SAVED_VIEW_UNAVAILABLE", dispatched = false)
        }
        return AgentToolResult(callId, ToolResultContent.Json(page(parts[0], entry, offset, modelName)), entry.originalError, sharedResult = true, dispatched = false)
    }

    private fun page(id: String, entry: Entry, offset: Int, modelName: String): JsonObject {
        val records = mutableListOf<JsonObject>()
        var used = entry.metadata.toString().toByteArray().size + 1200
        var next = offset
        while (next < entry.fragments.size) {
            val record = entry.fragments[next]
            val bytes = record.toString().toByteArray().size
            if (records.isNotEmpty() && used + bytes > pageBytes) break
            records += record; used += bytes; next++
        }
        val complete = next == entry.fragments.size
        return buildJsonObject {
            put("schema", SCHEMA); put("operation", entry.operation); put("local_page_complete", complete)
            put("metadata", entry.metadata); put("fragments", JsonArray(records)); put("fragment_offset", offset); put("total_fragments", entry.fragments.size)
            put("reconstruction", "Each pointer is a JSON pointer in the original result. String chunks use UTF-16 offset and total_characters. Read all local fragments before advancing the remote continuation.")
            if (!complete) {
                val cursor = "$id:$next"
                put("continuation_call", buildJsonObject { put("tool", modelName); put("arguments", buildJsonObject { put("view_cursor", cursor) }) })
            } else entry.remoteContinuation?.let { remote ->
                put("continuation", remote)
                if (remote is JsonObject) put("continuation_call", buildJsonObject { put("tool", modelName); put("arguments", remote) })
            }
        }
    }

    private fun fragment(pointer: String, value: JsonElement, target: MutableList<JsonObject>) {
        if (value.toString().toByteArray().size <= 2400) {
            target += buildJsonObject { put("pointer", pointer); put("value", value) }
            return
        }
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> fragment(pointer + "/" + key.replace("~", "~0").replace("/", "~1"), child, target) }
            is JsonArray -> value.forEachIndexed { index, child -> fragment("$pointer/$index", child, target) }
            is JsonPrimitive -> {
                val text = value.content
                var start = 0
                while (start < text.length) {
                    var end = minOf(start + 500, text.length)
                    if (end < text.length && text[end].isLowSurrogate()) end--
                    val chunk = text.substring(start, end)
                    target += buildJsonObject { put("pointer", pointer); put("value", chunk); put("offset", start); put("total_characters", text.length) }
                    start = end
                }
            }
        }
    }

    private fun overflow(upstream: AgentToolResult, operation: String, value: JsonElement): AgentToolResult = upstream.copy(
        content = ToolResultContent.Json(buildJsonObject {
            put("schema", SCHEMA); put("operation", operation); put("local_page_complete", false); put("error", "RETAINED_RESULT_CAPACITY")
            put("upstream_completed", !upstream.isError); put("upstream_dispatched", upstream.dispatched)
            put("receipt", JsonObject((value as? JsonObject).orEmpty().filter { (key, item) -> key in METADATA + RECEIPTS && item.toString().length <= 4096 }))
            put("next_step", "The upstream operation already returned. Reconcile its commit, stage, PR, run or release receipt with a read; never repeat a mutation merely to recover its output. For immutable reads request a smaller range or page.")
        }),
        isError = true,
        errorCode = "RETAINED_RESULT_CAPACITY"
    )

    private fun purge() { entries.filterValues { it.expires <= now() }.keys.toList().forEach(::evict) }
    private fun evict(id: String) { entries.remove(id)?.let { retainedBytes -= it.bytes } }
    companion object {
        const val SCHEMA = "github.operation.v1"
        private const val MAX_SOURCE_BYTES = 1024 * 1024
        private val METADATA = setOf("schema", "owner", "repo", "ref", "commit_sha", "tree_sha", "head_sha", "base_sha", "evidence_id", "complete", "status", "stage_id", "id", "number", "sha", "html_url", "url", "run_id", "release_id", "pull_number")
        private val RECEIPTS = setOf("commit", "pull_request", "release", "workflow_run", "receipt", "expected_head_sha")
        val shared = GitHubResultViews()
    }
}
