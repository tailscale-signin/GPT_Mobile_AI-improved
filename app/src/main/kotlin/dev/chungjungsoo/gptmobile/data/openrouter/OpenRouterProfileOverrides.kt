package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Raw presence survives round trips, including unknown future routing fields. */
data class OpenRouterProfileOverrides private constructor(val raw: JsonObject) {
    enum class Presence { ABSENT, INHERIT, EXPLICIT }

    fun presence(field: String): Presence = when {
        !raw.containsKey(field) -> Presence.ABSENT
        raw[field] == JsonNull -> Presence.INHERIT
        else -> Presence.EXPLICIT
    }

    fun replacing(field: String, value: JsonElement?): OpenRouterProfileOverrides =
        parse(JsonObject(if (value == null) raw - field else raw + (field to value)).toString())

    companion object {
        val GENERATION_FIELDS = setOf(
            "stream", "max_tokens", "temperature", "top_p", "top_k", "seed",
            "frequency_penalty", "presence_penalty", "repetition_penalty"
        )

        fun parse(value: String): OpenRouterProfileOverrides {
            val raw = Json.parseToJsonElement(value) as? JsonObject
                ?: throw IllegalArgumentException("OpenRouter profile overrides must be a JSON object.")
            for (field in GENERATION_FIELDS) {
                val element = raw[field] ?: continue
                if (element == JsonNull) continue
                val primitive = element as? JsonPrimitive
                require(primitive != null && !primitive.isString) { "$field must be a typed JSON value or null (inherit)." }
                val valid = when (field) {
                    "stream" -> primitive.booleanOrNull != null
                    "max_tokens", "top_k" -> primitive.intOrNull?.let { it >= 0 } == true
                    "seed" -> primitive.intOrNull != null
                    else -> primitive.doubleOrNull?.let { number ->
                        number.isFinite() &&
                            when (field) {
                                "temperature" -> number in 0.0..2.0
                                "top_p" -> number in 0.0..1.0
                                "repetition_penalty" -> number > 0.0
                                else -> number in -2.0..2.0
                            }
                    } == true
                }
                require(valid) { "Invalid OpenRouter $field override." }
            }
            require(raw["provider"] == null || raw["provider"] == JsonNull || raw["provider"] is JsonObject) {
                "OpenRouter provider routing must be an object or null."
            }
            return OpenRouterProfileOverrides(raw)
        }
    }
}
