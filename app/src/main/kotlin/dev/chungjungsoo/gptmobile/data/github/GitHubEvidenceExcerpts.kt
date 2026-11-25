package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Bounded, revision-bound source material. Quoted repository text is data, never an instruction. */
internal object GitHubEvidenceExcerpts {
    private val fields = setOf("content", "patch", "log", "logs", "text", "description", "body", "message", "summary")
    private val secret = Regex("(?i)token|secret|password|authorization|cookie")
    private val sha = Regex("[a-fA-F0-9]{40}")
    private const val MAX_EXCERPTS = 24
    private const val MAX_TEXT = 1800

    private val sourcePointer = Regex("""(/data)?/items/(\d+)/content#L\d+-L\d+""")

    /** Only a file range tied to its immutable source can bypass the observation clock. */
    fun pinnedSource(receipt: JsonObject, pointer: String): Boolean {
        if (receipt["immutable"] != JsonPrimitive(true) || (receipt["tool"] as? JsonPrimitive)?.content?.githubOperationFamily() != "github_read") return false
        val match = sourcePointer.matchEntire(pointer) ?: return false
        val result = receipt["result"] as? JsonObject ?: return false
        val root = (if (match.groupValues[1].isEmpty()) result else result["data"] as? JsonObject) ?: return false
        val index = match.groupValues[2].toIntOrNull() ?: return false
        val item = (root["items"] as? JsonArray)?.getOrNull(index) as? JsonObject ?: return false
        val path = (item["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return false
        val blob = (item["sha"] as? JsonPrimitive)?.contentOrNull?.takeIf(sha::matches) ?: return false
        val revision = (root["commit_sha"] as? JsonPrimitive)?.contentOrNull?.takeIf(sha::matches) ?: return false
        val args = receipt["arguments"] as? JsonObject
        if (listOf(item["commit_sha"], args?.get("commit_sha"), receipt["source_head_sha"]).filterIsInstance<JsonPrimitive>()
                .mapNotNull { it.contentOrNull }.any { !it.equals(revision, true) }
        ) {
            return false
        }
        if (item["expected_blob_sha"]?.let { it != JsonPrimitive(blob) } == true) return false
        val digest = receipt["result_sha256"] ?: return false
        if (receipt["review_projection"] == JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL")) {
            val excerpt = (receipt["retained_excerpts"] as? JsonObject)?.get(pointer) as? JsonObject ?: return false
            return receipt["verified_source_digest"] == digest &&
                excerpt["resource"] == JsonPrimitive(path) &&
                excerpt["revision"] == JsonPrimitive(revision)
        }
        return item["content"] is JsonPrimitive && digest == JsonPrimitive(GitHubRepositoryStateStore.digest(result.toString()))
    }

    private fun excerptOffset(item: JsonObject, firstLine: Int, excerpt: String): Long? {
        val text = (item["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val line = ((item["content_start_line"] ?: item["start_line"]) as? JsonPrimitive)?.intOrNull ?: return null
        val offset = ((item["range_offset"] ?: item["start_offset"]) as? JsonPrimitive)?.intOrNull ?: 0
        if (line < 1 || firstLine < line || offset < 0 || excerpt.isEmpty()) return null
        if ((item["start_offset"] as? JsonPrimitive)?.intOrNull?.let { it > 0 && "content_start_line" !in item } == true) return null
        var from = 0
        repeat(firstLine - line) {
            val newline = text.indexOf('\n', from)
            if (newline < 0) return null
            from = newline + 1
        }
        return (offset.toLong() + from).takeIf { text.startsWith(excerpt, from) }
    }

    /** Cross-reference a checked excerpt with the corresponding physical tool-output trace. */
    fun sourceClaimBinding(receipt: JsonObject, pointer: String, checked: JsonObject, entry: JsonObject): JsonObject? {
        if ((receipt["receipt_id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() ||
            checked["status"] != JsonPrimitive("VERIFIED") ||
            checked["receipt_id"] != receipt["receipt_id"] ||
            checked["pointer"] != JsonPrimitive(pointer) ||
            checked["observed"] != entry["value"] ||
            receipt["trusted"] != JsonPrimitive(true) ||
            receipt["success"] != JsonPrimitive(true) ||
            receipt["verification_eligible"] == JsonPrimitive(false) ||
            !pinnedSource(receipt, pointer)
        ) {
            return null
        }
        val match = sourcePointer.matchEntire(pointer) ?: return null
        val result = receipt["result"] as? JsonObject ?: return null
        val root = (if (match.groupValues[1].isEmpty()) result else result["data"] as? JsonObject) ?: return null
        val item = (root["items"] as? JsonArray)?.getOrNull(match.groupValues[2].toIntOrNull() ?: return null) as? JsonObject ?: return null
        val callId = (receipt["call_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val first = (entry["start_line"] as? JsonPrimitive)?.intOrNull ?: return null
        val last = (entry["end_line"] as? JsonPrimitive)?.intOrNull ?: return null
        val text = (entry["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val offset = excerptOffset(item, first, text)
            ?: (entry["source_offset_utf16"] as? JsonPrimitive)?.longOrNull?.takeIf {
                it >= 0 &&
                    receipt["review_projection"] == JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL") &&
                    (receipt["retained_excerpts"] as? JsonObject)?.get(pointer) == entry
            } ?: return null
        if (first < 1 ||
            first > last ||
            last.toLong() != first.toLong() + text.count { it == '\n' } ||
            !pointer.endsWith("#L$first-L$last") ||
            entry["resource"] != item["path"] ||
            entry["revision"] != root["commit_sha"] ||
            (item["start_offset"] as? JsonPrimitive)?.intOrNull?.let { it > 0 && "content_start_line" !in item } == true
        ) {
            return null
        }
        val rowHash = if (receipt["review_projection"] == JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL")) {
            (entry["source_row_sha256"] as? JsonPrimitive)?.contentOrNull ?: return null
        } else {
            val raw = (item["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(raw)
        }
        val rowLine = ((item["content_start_line"] ?: item["start_line"]) as? JsonPrimitive)?.intOrNull ?: return null
        val rowOffset = ((item["range_offset"] ?: item["start_offset"]) as? JsonPrimitive)?.intOrNull ?: 0
        return buildJsonObject {
            put("source_row_sha256", rowHash)
            put("source_row_start_line", rowLine)
            put("source_row_offset_utf16", rowOffset)
            put("call_id_sha256", dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(callId))
            put("commit_sha", root.getValue("commit_sha"))
            put("blob_sha", item.getValue("sha"))
            put("path_sha256", dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256((item.getValue("path") as JsonPrimitive).content))
            put("start_line", entry.getValue("start_line"))
            put("end_line", last - if (text.endsWith('\n')) 1 else 0)
            put("offset_utf16", offset)
            put("length_utf16", text.length)
            put("excerpt_sha256", dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(text))
        }
    }

    fun collect(receipt: JsonObject): Map<String, JsonObject> {
        if (receipt["success"] != JsonPrimitive(true) || receipt["trusted"] != JsonPrimitive(true)) return emptyMap()
        if (receipt["review_projection"] == JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL")) {
            return (receipt["retained_excerpts"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonObject)?.let { key to it }
            }.toMap()
        }
        val result = receipt["result"] ?: return emptyMap()
        val root = (result as? JsonObject)?.let { it["data"] as? JsonObject ?: it }
        val arguments = receipt["arguments"] as? JsonObject
        val revision = listOf(receipt["source_head_sha"], root?.get("commit_sha"), root?.get("head_sha"), arguments?.get("commit_sha"), arguments?.get("ref"), receipt["branch"], root?.get("sha"))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { sha.matches(it) } ?: return emptyMap()
        val found = linkedMapOf<String, JsonObject>()
        fun visit(value: JsonElement, pointer: String, depth: Int, resource: String, firstLine: Int, sourceRevision: String) {
            if (found.size >= MAX_EXCERPTS || depth > 8) return
            when (value) {
                is JsonObject -> {
                    // A commit-list item's SHA owns its message; a file blob SHA is not a commit revision.
                    val itemRevision = (value["sha"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { value["commit"] is JsonObject && sha.matches(it) } ?: sourceRevision
                    val path = (value["path"] as? JsonPrimitive)?.contentOrNull ?: resource
                    val start = ((value["content_start_line"] ?: value["start_line"]) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: firstLine
                    for ((key, child) in value) {
                        if (found.size >= MAX_EXCERPTS) break
                        if (secret.containsMatchIn(key)) continue
                        val next = "$pointer/${key.replace("~", "~0").replace("/", "~1")}"
                        val text = (child as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        if (key in fields && text != null && text.isNotBlank()) {
                            // Do not send credentials in source excerpts to another model or into exports.
                            val clean = (dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(JsonPrimitive(text.take(48_000))) as JsonPrimitive).content
                            val rowHash by lazy(LazyThreadSafetyMode.NONE) { dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(text) }
                            var offset = start
                            val sourceLines = clean.lineSequence().take(241).toList()
                            val sourceTruncated = text.length > 48_000 || sourceLines.size > 240
                            for (lines in sourceLines.asSequence().take(240).chunked(24)) {
                                if (found.size >= MAX_EXCERPTS) break
                                val joined = lines.joinToString("\n")
                                val chunk = joined.take(MAX_TEXT)
                                val end = offset + chunk.count { it == '\n' }
                                val id = "$next#L$offset-L$end"
                                found[id] = buildJsonObject {
                                    put("value", chunk)
                                    put("resource", path.ifBlank { next })
                                    put("revision", itemRevision)
                                    put("start_line", offset)
                                    put("end_line", end)
                                    if (key == "content" && sourcePointer.matches(id)) {
                                        excerptOffset(value, offset, chunk)?.let {
                                            put("source_offset_utf16", it)
                                            put("source_row_sha256", rowHash)
                                        }
                                    }
                                    put(
                                        "kind",
                                        when (key) {
                                            "patch" -> "diff"
                                            "log", "logs" -> "log"
                                            "content", "text" -> "source"
                                            else -> "description"
                                        }
                                    )
                                    put("truncated", chunk.length < joined.length)
                                    put("source_truncated", sourceTruncated)
                                }
                                offset += lines.size
                            }
                        } else if (child is JsonObject || child is JsonArray) {
                            visit(child, next, depth + 1, path, start, itemRevision)
                        }
                    }
                }
                is JsonArray -> value.take(32).forEachIndexed { index, child -> visit(child, "$pointer/$index", depth + 1, resource, firstLine, sourceRevision) }
                else -> Unit
            }
        }
        visit(result, "", 0, "", 1, revision)
        return found
    }
}
