package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object GitHubPatchStager {
    fun apply(originals: Map<String, Pair<String, String>>, edits: JsonArray): JsonArray {
        require(edits.isNotEmpty() && edits.size <= 100) { "Supply 1–100 exact edits." }
        val changed = linkedMapOf<String, String>()
        for (row in edits) {
            val edit = row as? JsonObject ?: error("Edit must be an object.")
            val path = edit["path"]?.jsonPrimitive?.content.orEmpty().also { GitHubWorkspaceClient.filePath(it) }
            val original = originals[path] ?: error("Original file was not read: $path")
            require(edit["expected_blob_sha"]?.jsonPrimitive?.content == original.first) { "Original blob changed or was not identified: $path" }
            val old = edit["old_text"]?.jsonPrimitive?.contentOrNull ?: error("old_text is required.")
            val replacement = edit["new_text"]?.jsonPrimitive?.contentOrNull ?: error("new_text is required.")
            require(old.isNotEmpty()) { "An empty anchor cannot identify an exact edit." }
            val before = changed[path] ?: original.second
            val positions = Regex(Regex.escape(old)).findAll(before).map { it.range.first }.take(10001).toList()
            val occurrence = edit["occurrence"]?.jsonPrimitive?.intOrNull
            require(positions.isNotEmpty()) { "Exact edit anchor was not found: $path" }
            require(occurrence != null || positions.size == 1) { "Ambiguous edit anchor; supply an explicit occurrence: $path" }
            val selected = occurrence ?: 1
            require(selected in 1..positions.size) { "Edit occurrence is outside the original text." }
            val at = positions[selected - 1]
            val after = before.replaceRange(at, at + old.length, replacement)
            require(after.toByteArray().size <= 2 * 1024 * 1024 && '\u0000' !in after) { "Patched text exceeds its file byte budget." }
            changed[path] = after
        }
        require(changed.size <= 20 && changed.values.sumOf { it.toByteArray().size } <= 4 * 1024 * 1024) { "Patch exceeds its change-set byte budget." }
        return JsonArray(changed.map { (path, content) -> buildJsonObject {
            put("path", path)
            put("expected_blob_sha", originals.getValue(path).first)
            put("content", content)
        } })
    }
}
