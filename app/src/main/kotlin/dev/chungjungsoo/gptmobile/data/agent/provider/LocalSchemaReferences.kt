package dev.chungjungsoo.gptmobile.data.agent.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Expand local JSON pointers; never drop a reference and silently widen its schema. */
internal fun resolveLocalSchemaReferences(root: JsonObject): JsonObject {
    var expanded = 0
    fun visit(value: JsonElement, path: Set<String>, depth: Int): JsonElement {
        require(depth <= 32 && ++expanded <= 4096) { "Tool schema reference expansion exceeds its limit." }
        return when (value) {
            is JsonArray -> JsonArray(value.map { visit(it, path, depth + 1) })
            is JsonObject -> {
                val ref = (value["\$ref"] as? JsonPrimitive)?.content
                if (ref != null) {
                    require(ref.startsWith("#/") && ref !in path) { "Unsupported or cyclic tool schema reference: $ref" }
                    val target = ref.removePrefix("#/").split('/').fold(root as JsonElement) { current, part ->
                        val key = part.replace("~1", "/").replace("~0", "~")
                        (current as? JsonObject)?.get(key) ?: error("Missing tool schema reference: $ref")
                    }
                    val resolved = visit(target, path + ref, depth + 1) as? JsonObject ?: error("Tool schema reference is not an object: $ref")
                    require(value.keys.all { it == "\$ref" || it in setOf("title", "description", "default", "examples") }) {
                        "Schema reference with constraint siblings is unsupported; normalize it before adaptation."
                    }
                    JsonObject(resolved + value.filterKeys { it != "\$ref" }.mapValues { visit(it.value, path, depth + 1) })
                } else {
                    JsonObject(
                        value.filterKeys { it !in setOf("\$defs", "definitions") }.mapValues { (key, child) ->
                            when {
                                key in setOf("properties", "patternProperties", "dependentSchemas") && child is JsonObject ->
                                    JsonObject(child.mapValues { visit(it.value, path, depth + 1) })
                                key in setOf("enum", "const", "default", "examples") -> child
                                else -> visit(child, path, depth + 1)
                            }
                        }
                    )
                }
            }
            else -> value
        }
    }
    return visit(root, emptySet(), 0) as JsonObject
}
