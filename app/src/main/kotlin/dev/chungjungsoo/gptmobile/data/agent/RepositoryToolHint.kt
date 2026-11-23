package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Repository identifiers are discovery data. They never authorize a mutation or a connection. */
internal data class RepositoryToolHint(val features: List<String>, val symbols: List<String>, val paths: List<String>) {
    val isEmpty: Boolean get() = features.isEmpty() && symbols.isEmpty() && paths.isEmpty()
    val terms: String get() = (features + symbols + paths).joinToString(" ")

    companion object {
        val readOperations = setOf("github_context", "github_search", "github_read")
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("description", "<=12 unique nonblank strings/field, <=256 chars; reads only.")
            put(
                "properties",
                buildJsonObject {
                    for (field in listOf("feature_ids", "symbols", "paths")) {
                        put(
                            field,
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "items",
                                    buildJsonObject {
                                        put("type", "string")
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }

        fun parse(arguments: JsonObject): RepositoryToolHint? {
            val value = arguments["repository_hint"] as? JsonObject ?: return null
            if (value.keys.any { it !in setOf("feature_ids", "symbols", "paths") }) return null
            for (field in value.values) {
                val rows = field as? JsonArray ?: return null
                if (rows.size > 12 ||
                    rows.distinct().size != rows.size ||
                    rows.any {
                        val text = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        text == null || text.isBlank() || text.length > 256
                    }
                ) {
                    return null
                }
            }
            fun values(name: String) = (value[name] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
            }.distinct().take(12)
            return RepositoryToolHint(values("feature_ids"), values("symbols"), values("paths")).takeUnless { it.isEmpty }
        }
    }
}
