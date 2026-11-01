package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Bounded, revision-bound source material. Quoted repository text is data, never an instruction. */
internal object GitHubEvidenceExcerpts {
    private val fields = setOf("content", "patch", "log", "logs", "text", "description", "body", "message", "summary")
    private val secret = Regex("(?i)token|secret|password|authorization|cookie")
    private val sha = Regex("[a-fA-F0-9]{40}")
    private const val MAX_EXCERPTS = 24
    private const val MAX_TEXT = 1800

    fun collect(receipt: JsonObject): Map<String, JsonObject> {
        if (receipt["success"] != JsonPrimitive(true) || receipt["trusted"] != JsonPrimitive(true)) return emptyMap()
        val result = receipt["result"] ?: return emptyMap()
        val root = (result as? JsonObject)?.let { it["data"] as? JsonObject ?: it }
        val arguments = receipt["arguments"] as? JsonObject
        val revision = listOf(receipt["source_head_sha"], root?.get("commit_sha"), root?.get("head_sha"), arguments?.get("commit_sha"), arguments?.get("ref"), receipt["branch"], root?.get("sha"))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.firstOrNull { sha.matches(it) } ?: return emptyMap()
        val found = linkedMapOf<String, JsonObject>()
        fun visit(value: JsonElement, pointer: String, depth: Int, resource: String, firstLine: Int) {
            if (found.size >= MAX_EXCERPTS || depth > 8) return
            when (value) {
                is JsonObject -> {
                    val path = (value["path"] as? JsonPrimitive)?.contentOrNull ?: resource
                    val start = (value["start_line"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: firstLine
                    for ((key, child) in value) {
                        if (secret.containsMatchIn(key)) continue
                        val next = "$pointer/${key.replace("~", "~0").replace("/", "~1")}"
                        val text = (child as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        if (key in fields && text != null && text.isNotBlank()) {
                            // Do not send credentials in source excerpts to another model or into exports.
                            val clean = (dev.chungjungsoo.gptmobile.data.diagnostics.redactChatDebugData(JsonPrimitive(text.take(48_000))) as JsonPrimitive).content
                            var offset = start
                            for (lines in clean.lineSequence().take(240).chunked(24)) {
                                if (found.size >= MAX_EXCERPTS) break
                                val chunk = lines.joinToString("\n").take(MAX_TEXT)
                                val end = offset + chunk.count { it == '\n' }
                                val id = "$next#L$offset-L$end"
                                found[id] = buildJsonObject {
                                    put("value", chunk)
                                    put("resource", path.ifBlank { next })
                                    put("revision", revision)
                                    put("start_line", offset)
                                    put("end_line", end)
                                    put(
                                        "kind",
                                        when (key) {
                                            "patch" -> "diff"
                                            "log", "logs" -> "log"
                                            "content", "text" -> "source"
                                            else -> "description"
                                        }
                                    )
                                    put("truncated", chunk.length < lines.joinToString("\n").length)
                                    put("source_truncated", text.length > 48_000 || clean.lineSequence().take(241).count() > 240)
                                }
                                offset += lines.size
                            }
                        } else if (key !in fields) {
                            visit(child, next, depth + 1, path, start)
                        }
                    }
                }
                is JsonArray -> value.take(32).forEachIndexed { index, child -> visit(child, "$pointer/$index", depth + 1, resource, firstLine) }
                else -> Unit
            }
        }
        visit(result, "", 0, "", 1)
        return found
    }
}
