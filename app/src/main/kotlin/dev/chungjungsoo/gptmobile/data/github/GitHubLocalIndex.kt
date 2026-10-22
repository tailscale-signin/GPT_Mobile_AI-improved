package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A bounded lexical index. It reports its coverage and never claims compiler symbol resolution. */
internal object GitHubLocalIndex {
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")
    private val definition = Regex("\\b(?:class|interface|object|fun|def|function|struct|enum|trait|type|const|let|var|val)\\s+([A-Za-z_][A-Za-z_0-9]*)")
    fun build(text: String): JsonObject {
        val references = mutableListOf<JsonObject>()
        val definitions = mutableListOf<JsonObject>()
        val imports = mutableListOf<JsonObject>()
        var totalReferences = 0
        var totalDefinitions = 0
        text.lineSequence().forEachIndexed { index, line ->
            definition.findAll(line).forEach { match ->
                totalDefinitions++
                if (definitions.size < 2000) definitions += occurrence(match.groupValues[1], index + 1)
            }
            identifier.findAll(line).forEach { match ->
                totalReferences++
                if (references.size < 6000) references += occurrence(match.value, index + 1)
            }
            if (imports.size < 1000 && Regex("^\\s*(?:import|from|#include|use)\\b").containsMatchIn(line)) imports += occurrence(line.trim().take(300), index + 1)
        }
        return buildJsonObject {
            put("kind", "lexical")
            put("definitions", JsonArray(definitions))
            put("references", JsonArray(references))
            put("imports", JsonArray(imports))
            put("definitions_complete", totalDefinitions == definitions.size)
            put("references_complete", totalReferences == references.size)
            put("compiler_resolution", false)
            put("line_count", text.count { it == '\n' } + 1)
        }
    }
    private fun occurrence(name: String, line: Int) = buildJsonObject {
        put("name", name)
        put("line", line)
    }
}
