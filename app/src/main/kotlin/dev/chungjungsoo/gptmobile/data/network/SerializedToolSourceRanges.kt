package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Reads only provider-native tool outputs. A user's quoted tool envelope never proves delivery. */
internal object SerializedToolSourceRanges {
    const val LIMIT = 32
    private const val MAX_ENCODED_CHARACTERS = 262_144
    fun oversized(payload: JsonElement?) = (payload as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { it.length > MAX_ENCODED_CHARACTERS } == true
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

    data class Range(val commit: String, val blob: String, val pathHash: String, val line: Int, val offset: Int, val content: String, val partial: Boolean) {
        fun evidence(callId: String?) = buildJsonObject {
            callId?.let { put("call_id_sha256", DiagnosticRecordChunks.sha256(it)) }
            put("commit_sha", commit)
            put("blob_sha", blob)
            put("path_sha256", pathHash)
            put("start_line", line)
            val end = line.toLong() + content.count { it == '\n' } - if (content.endsWith('\n')) 1 else 0
            put("end_line", end)
            put("offset_utf16", offset)
            put("length_utf16", content.length)
            put("excerpt_sha256", DiagnosticRecordChunks.sha256(content))
            put("partial_source", partial)
        }
    }

    fun ranges(payload: JsonElement?, retainedRead: Boolean = false): List<Range> {
        val raw = (payload as? JsonPrimitive)?.takeIf { it.isString }?.content
        val envelope = payload as? JsonObject ?: raw?.takeIf { it.length <= MAX_ENCODED_CHARACTERS && it.startsWith('{') && (retainedRead || "github_read" in it) }?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        } ?: return emptyList()
        val page = if ("saved_page_reading" in envelope || "assist" in envelope) envelope["data"] as? JsonObject ?: return emptyList() else envelope
        val source = when {
            page.text("operation") == "github_read" && page.text("schema") == "github.operation.v1" ->
                if (page["source_page"] == JsonPrimitive(true)) page else page["data"] as? JsonObject
            retainedRead && "schema" !in page && "operation" !in page -> page
            else -> null
        } ?: return emptyList()
        val commit = source.text("commit_sha")?.takeIf(revision::matches) ?: return emptyList()
        return (source["items"] as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>().mapNotNull { row ->
            if ("commit_sha" in row && row.text("commit_sha") != commit) return@mapNotNull null
            val content = (row["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val path = row.text("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val blob = row.text("sha")?.takeIf(revision::matches) ?: return@mapNotNull null
            if ("expected_blob_sha" in row && row.text("expected_blob_sha") != blob) return@mapNotNull null
            val start = row.number("range_offset") ?: row.number("start_offset") ?: 0
            val line = row.number("content_start_line") ?: row.number("start_line") ?: return@mapNotNull null
            if (start < 0 || line < 1 || (row.number("start_offset") ?: 0) > 0 && "content_start_line" !in row) return@mapNotNull null
            val partial = row["range_end"] == JsonPrimitive(false) || row["range_complete"] == JsonPrimitive(false) || row["truncated"] == JsonPrimitive(true) || start > 0
            Range(commit, blob, DiagnosticRecordChunks.sha256(path), line, start, content, partial)
        }.take(LIMIT + 1).toList()
    }

    fun visit(request: JsonObject?, record: (String?, JsonElement?) -> Unit): Boolean {
        if (request == null) return false
        val messages = (request["messages"] ?: request["input"] ?: request["contents"]) as? JsonArray ?: return false
        fun blocks(id: String?, content: JsonElement?) {
            if (content is JsonArray) {
                record(id, null)
                for (element in content) {
                    val block = element as? JsonObject ?: continue
                    if (block.text("type") in setOf("text", "output_text", "input_text")) record(id, block["text"])
                }
            } else {
                record(id, content)
            }
        }
        for (element in messages) {
            val message = element as? JsonObject ?: continue
            when {
                message.text("role") == "tool" -> blocks(message.text("tool_call_id"), message["content"])
                message.text("type") == "function_call_output" -> blocks(message.text("call_id"), message["output"])
                message.text("role") == "user" -> {
                    (message["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("type") == "tool_result" }.forEach {
                        blocks(it.text("tool_use_id"), it["content"])
                    }
                    (message["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach {
                        val function = it["functionResponse"] as? JsonObject ?: return@forEach
                        val response = function["response"] as? JsonObject
                        record(function.text("id"), response?.get("result") ?: response?.get("output") ?: response)
                    }
                }
            }
        }
        return true
    }

    fun from(request: JsonObject?): JsonArray? {
        val result = mutableListOf<JsonObject>()
        visit(request) { id, payload ->
            if (result.size < LIMIT) ranges(payload).take(LIMIT - result.size).forEach { result += it.evidence(id) }
        }
        return result.takeIf { it.isNotEmpty() }?.let(::JsonArray)
    }
}
