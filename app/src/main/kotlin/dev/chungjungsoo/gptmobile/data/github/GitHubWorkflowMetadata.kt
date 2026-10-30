package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read-only YAML outline, never an evaluator. Expressions and unsupported syntax remain explicit. */
internal object GitHubWorkflowMetadata {
    private val keyPattern = Regex("^([A-Za-z_][A-Za-z_0-9-]*|\"[^\"]+\"|'[^']+'):\\s*(.*)$")
    private data class Entry(val indent: Int, val key: String, val value: String, val line: Int, val parents: List<String>)
    private fun scalar(value: String) = value.trim().removeSurrounding("\"").removeSurrounding("'")
    private fun list(value: String) = value.removeSurrounding("[", "]").split(',').map(::scalar).filter(String::isNotEmpty)

    fun parse(source: String): JsonObject {
        require(source.length <= 256 * 1024) { "Workflow definition exceeds the metadata budget." }
        val entries = mutableListOf<Entry>()
        val stack = mutableListOf<Entry>()
        val warnings = linkedSetOf<String>()
        var blockIndent: Int? = null
        for ((index, raw) in source.lines().withIndex()) {
            if (entries.size >= 10_000) {
                warnings += "Metadata entry limit reached; inspect the remaining source."
                break
            }
            if (raw.isBlank() || raw.trimStart().startsWith('#')) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            if (blockIndent != null && indent > blockIndent) continue
            blockIndent = null
            if ('\t' in raw.take(indent)) warnings += "Tab indentation requires source inspection."
            while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeAt(stack.lastIndex)
            val trimmed = raw.trim().removePrefix("- ")
            val match = keyPattern.find(trimmed)
            if (match == null) {
                warnings += "Sequence or complex YAML syntax requires source inspection."
                continue
            }
            val key = scalar(match.groupValues[1])
            // Preserve quoted # characters; comments are only stripped from unquoted scalars.
            val value = match.groupValues[2].let { if (it.startsWith('"') || it.startsWith('\'')) it else it.substringBefore(" #") }.trim()
            if (value.startsWith('|') || value.startsWith('>')) {
                blockIndent = indent
                if (key != "run") warnings += "Block scalar metadata requires source inspection."
            }
            if (value.startsWith('&') || value.startsWith('*') || value.startsWith('{') || "<<:" in trimmed) warnings += "Anchors, aliases or flow maps require source inspection."
            val entry = Entry(indent, key, value, index + 1, stack.map { it.key })
            entries += entry
            if (value.isEmpty()) stack += entry
        }
        val byParent = entries.groupBy { it.parents }
        val triggers = byParent[listOf("on")].orEmpty().map { it.key } +
            entries.filter { it.key == "on" && it.parents.isEmpty() && it.value.isNotEmpty() }.flatMap { list(it.value) }
        val inputs = entries.filter { it.parents in listOf(listOf("on", "workflow_dispatch", "inputs"), listOf("on", "workflow_call", "inputs")) }.map { input ->
            buildJsonObject {
                put("name", input.key)
                put("trigger", input.parents[1])
                byParent[input.parents + input.key].orEmpty().filter { it.key in setOf("description", "required", "default", "type", "options") }.forEach { put(it.key, scalar(it.value)) }
                put("line", input.line)
            }
        }
        val artifacts = entries.filter { it.key == "uses" && scalar(it.value).startsWith("actions/upload-artifact@") }.map { upload ->
            val end = entries.firstOrNull { it.line > upload.line && (it.indent < upload.indent || (it.indent == upload.indent && it.key in setOf("name", "uses", "run"))) }?.line ?: Int.MAX_VALUE
            buildJsonObject {
                put("line", upload.line)
                entries.filter { it.line > upload.line && it.line < end && it.key in setOf("name", "path", "retention-days", "compression-level", "if-no-files-found") }.forEach { put(it.key, scalar(it.value)) }
            }
        }
        val jobs = entries.filter { it.parents == listOf("jobs") }.map { job ->
            buildJsonObject {
                put("id", job.key)
                byParent[listOf("jobs", job.key)].orEmpty().filter { it.key in setOf("needs", "runs-on", "timeout-minutes", "if") }.forEach { put(it.key, scalar(it.value)) }
            }
        }
        return buildJsonObject {
            entries.firstOrNull { it.key == "name" && it.parents.isEmpty() }?.let { put("name", scalar(it.value)) }
            put("triggers", JsonArray(triggers.distinct().map(::JsonPrimitive)))
            put("inputs", JsonArray(inputs))
            put("artifacts", JsonArray(artifacts))
            put("jobs", JsonArray(jobs))
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put("parser", "bounded_yaml_outline")
            put("evaluation_complete", false)
            put("expressions_evaluated", false)
        }
    }
}
