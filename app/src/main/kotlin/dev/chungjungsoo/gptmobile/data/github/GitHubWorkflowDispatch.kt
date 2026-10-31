package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** A deliberately bounded dispatch contract parser. Unsupported trigger YAML blocks dispatch. */
internal object GitHubWorkflowDispatch {
    class Invalid(val code: String, message: String) : IllegalArgumentException(message)
    private data class Line(val indent: Int, val key: String, val value: String)
    private val key = Regex("^(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z_][A-Za-z_0-9-]*)):\\s*(.*)$")
    private fun scalar(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")

    fun validate(source: String, inputs: JsonObject, validateInputs: Boolean = true) {
        fun invalid(code: String, detail: String): Nothing = throw Invalid(code, "$detail No workflow mutation was sent.")
        if (source.length > 256 * 1024) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Workflow definition exceeds the inspection budget.")
        val raw = source.lineSequence().filterNot { it.isBlank() || it.trimStart().startsWith('#') }.toList()
        val on = raw.indexOfFirst { it.firstOrNull()?.isWhitespace() == false && key.matchEntire(it)?.let { m -> listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).first { v -> v.isNotEmpty() } == "on" } == true }
        if (on < 0) invalid("WORKFLOW_DISPATCH_UNSUPPORTED", "The workflow has no observed on/workflow_dispatch trigger.")
        val section = raw.drop(on).takeWhileIndexed { index, line -> index == 0 || line.first().isWhitespace() }
        val header = key.matchEntire(section.first())!!.groupValues[4].substringBefore(" #").trim()
        if (header.isNotEmpty()) {
            val events = header.removeSurrounding("[", "]").split(',').map(::scalar)
            if (header.startsWith('{') || events.any { !it.matches(Regex("[a-z_]+")) }) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Inspect unsupported trigger YAML; no dispatch contract was inferred.")
            if ("workflow_dispatch" !in events) invalid("WORKFLOW_DISPATCH_UNSUPPORTED", "This workflow does not support workflow_dispatch.")
            if (validateInputs && inputs.isNotEmpty()) invalid("WORKFLOW_INPUTS_INVALID", "This workflow declares no dispatch inputs. Put the branch/tag in top-level ref.")
            return
        }
        val lines = mutableListOf<Line>()
        var block: Int? = null
        for (line in section.drop(1)) {
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (block?.let { indent > it } == true) continue
            block = null
            if ('\t' in line.take(indent)) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Tab-indented trigger YAML needs inspection.")
            val match = key.matchEntire(line.trim())
            if (match == null) {
                if (line.trimStart().startsWith("- ")) {
                    lines += Line(indent, "-", scalar(line.trim().removePrefix("- ")))
                    continue
                }
                invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Unsupported trigger YAML needs inspection.")
            }
            val name = listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]).first { it.isNotEmpty() }
            val value = match.groupValues[4].let { if (it.startsWith('"') || it.startsWith('\'')) it else it.substringBefore(" #") }.trim()
            if (value.startsWith('|') || value.startsWith('>')) block = indent
            lines += Line(indent, name, value)
        }
        val eventIndent = lines.minOfOrNull { it.indent } ?: invalid("WORKFLOW_DISPATCH_UNSUPPORTED", "No workflow_dispatch trigger was observed.")
        val dispatch = lines.indexOfFirst { it.indent == eventIndent && it.key == "workflow_dispatch" }
        if (dispatch < 0) invalid("WORKFLOW_DISPATCH_UNSUPPORTED", "This workflow does not support workflow_dispatch.")
        val event = lines[dispatch]
        val definition = lines.drop(dispatch + 1).takeWhile { it.indent > eventIndent }
        if (event.value !in setOf("", "{}", "null", "~") || definition.any { it.key == "<<" || it.value.startsWith('&') || it.value.startsWith('*') || it.value.startsWith('!') }) {
            invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Dispatch aliases, tags or flow mappings require inspection.")
        }
        if (!validateInputs) return
        val inputIndex = definition.indexOfFirst { it.key == "inputs" }
        if (inputIndex < 0) {
            if (inputs.isNotEmpty()) invalid("WORKFLOW_INPUTS_INVALID", "This workflow declares no dispatch inputs. Put the branch/tag in top-level ref.")
            return
        }
        val inputLine = definition[inputIndex]
        if (inputLine.value !in setOf("", "{}")) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Dispatch input mappings require inspection.")
        val inputLines = definition.drop(inputIndex + 1).takeWhile { it.indent > inputLine.indent }
        val inputIndent = inputLines.minOfOrNull { it.indent }
        val declarations = inputLines.filter { it.indent == inputIndent }
        val names = declarations.map { it.key }
        if (names.distinct().size != names.size || names.size > 25) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Dispatch input declarations are duplicated or exceed the inspection bound.")
        val extra = inputs.keys - names.toSet()
        if (extra.isNotEmpty()) invalid("WORKFLOW_INPUTS_INVALID", "Undeclared dispatch inputs: ${extra.joinToString()}. Put the branch/tag in top-level ref unless a separate input named ref is declared.")
        for (declaration in declarations) {
            if (declaration.value.isNotEmpty()) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Flow-style input declarations require inspection.")
            val start = inputLines.indexOf(declaration)
            val fields = inputLines.drop(start + 1).takeWhile { it.indent > declaration.indent }
            val fieldIndent = fields.minOfOrNull { it.indent }
            val direct = fields.filter { it.indent == fieldIndent }
            if (direct.map { it.key }.distinct().size != direct.size) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Duplicated input properties require inspection.")
            val properties = direct.associate { it.key to scalar(it.value) }
            if (properties["required"]?.let { it !in setOf("true", "false") } == true) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Input required flags must be verified booleans.")
            val supplied = inputs[declaration.key]
            if (supplied == null) {
                if (properties["required"] == "true" && "default" !in properties) invalid("WORKFLOW_INPUTS_INVALID", "Required input is missing: ${declaration.key}.")
                continue
            }
            val value = supplied as? JsonPrimitive ?: invalid("WORKFLOW_INPUTS_INVALID", "Input ${declaration.key} must be a scalar.")
            val type = properties["type"] ?: "string"
            val valid = when (type) {
                "boolean" -> value.booleanOrNull != null
                "number" -> value.doubleOrNull?.isFinite() == true
                "string", "environment" -> value.isString
                "choice" -> {
                    val option = fields.firstOrNull { it.key == "options" }
                    val options = if (option == null) {
                        emptyList()
                    } else if (option.value.startsWith('[') && option.value.endsWith(']')) {
                        option.value.removeSurrounding("[", "]").split(',').map(::scalar)
                    } else {
                        fields.drop(fields.indexOf(option) + 1).takeWhile { it.indent > option.indent }.filter { it.key == "-" }.map { it.value }
                    }
                    if (options.isEmpty()) invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Choice input ${declaration.key} has no verified options.")
                    value.isString && value.content in options
                }
                else -> invalid("WORKFLOW_SCHEMA_UNVERIFIED", "Unsupported dispatch input type: $type.")
            }
            if (!valid || value.content.length > 16_000) invalid("WORKFLOW_INPUTS_INVALID", "Input ${declaration.key} does not match declared type $type/options.")
        }
    }

    private inline fun <T> List<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean) = withIndex().takeWhile { predicate(it.index, it.value) }.map { it.value }
}
