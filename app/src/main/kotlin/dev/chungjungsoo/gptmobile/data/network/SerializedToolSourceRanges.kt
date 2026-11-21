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

/** Records exact source projections admitted to the serialized request, without storing code or paths. */
internal object SerializedToolSourceRanges {
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun from(request: JsonObject?): JsonArray? {
        if (request == null) return null
        val ranges = mutableListOf<JsonObject>()
        fun record(payload: JsonElement?, id: String?) {
            if (ranges.size >= 32) return
            val raw = (payload as? JsonPrimitive)?.takeIf { it.isString }?.content
            val page = payload as? JsonObject ?: raw?.takeIf { it.length <= 65_536 && it.startsWith('{') && "github_read" in it }?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            } ?: return
            if (page.text("operation") != "github_read" || page.text("schema") != "github.operation.v1") return
            val source = if (page["source_page"] == JsonPrimitive(true)) page else page["data"] as? JsonObject ?: return
            val commit = source.text("commit_sha")?.takeIf(revision::matches) ?: return
            for (row in (source["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                if (ranges.size >= 32) return
                val content = (row["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val path = row.text("path")?.takeIf { it.isNotBlank() } ?: continue
                val blob = row.text("sha")?.takeIf(revision::matches) ?: continue
                val start = ((row["range_offset"] ?: row["start_offset"]) as? JsonPrimitive)?.intOrNull ?: 0
                val line = ((row["content_start_line"] ?: row["start_line"]) as? JsonPrimitive)?.intOrNull ?: continue
                if (start < 0 || line <= 0) continue
                ranges += buildJsonObject {
                    id?.let { put("call_id_sha256", DiagnosticRecordChunks.sha256(it)) }
                    put("commit_sha", commit)
                    put("blob_sha", blob)
                    put("path_sha256", DiagnosticRecordChunks.sha256(path))
                    put("start_line", line)
                    put("offset_utf16", start)
                    put("length_utf16", content.length)
                    put("excerpt_sha256", DiagnosticRecordChunks.sha256(content))
                }
            }
        }
        val messages = (request["messages"] ?: request["input"] ?: request["contents"]) as? JsonArray ?: return null
        for (element in messages) {
            if (ranges.size >= 32) break
            val message = element as? JsonObject ?: continue
            val content = message["content"]
            when {
                message.text("role") == "tool" -> {
                    if (content is JsonArray) {
                        content.filterIsInstance<JsonObject>().forEach { record(it["text"], message.text("tool_call_id")) }
                    } else {
                        record(content, message.text("tool_call_id"))
                    }
                }
                message.text("type") == "function_call_output" -> record(message["output"], message.text("call_id"))
                message.text("role") == "user" -> {
                    (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("type") == "tool_result" }.forEach { block ->
                        val result = block["content"]
                        if (result is JsonArray) {
                            result.filterIsInstance<JsonObject>().forEach { record(it["text"], block.text("tool_use_id")) }
                        } else {
                            record(result, block.text("tool_use_id"))
                        }
                    }
                    (message["parts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach {
                        val function = it["functionResponse"] as? JsonObject ?: return@forEach
                        val response = function["response"] as? JsonObject ?: return@forEach
                        record(response["result"] ?: response["output"] ?: response, function.text("id"))
                    }
                }
            }
        }
        return ranges.takeIf { it.isNotEmpty() }?.let(::JsonArray)
    }
}
