package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** JSON Schema types describe the answer, not the provider's response-format selector. */
internal object ProviderResponseFormatPolicy {
    fun chatCompletions(clientType: ClientType, schemaJson: String?): JsonObject? {
        if (schemaJson == null || clientType !in setOf(ClientType.LLAMA, ClientType.OPENROUTER)) return null
        val schema = Json.parseToJsonElement(schemaJson).jsonObject
        // Existing GitHub contracts already include a named, strict provider envelope.
        if ((schema["type"] as? JsonPrimitive)?.content in setOf("text", "json_object", "json_schema")) return schema
        return if (clientType == ClientType.LLAMA) {
            buildJsonObject {
                put("type", "json_object")
                put("schema", schema)
            }
        } else {
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", "final_response")
                        // A raw schema need not meet the provider's narrower strict subset.
                        put("schema", schema)
                    }
                )
            }
        }
    }
}
