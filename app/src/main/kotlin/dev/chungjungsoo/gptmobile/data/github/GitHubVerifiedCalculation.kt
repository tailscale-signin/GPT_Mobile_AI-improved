package dev.chungjungsoo.gptmobile.data.github

import java.math.BigDecimal
import java.math.MathContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object GitHubVerifiedCalculation {
    fun evaluate(claim: JsonObject, verify: (JsonObject) -> JsonObject): JsonObject {
        val inputs = (claim["sources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val verified = inputs.map(verify)
        val numbers = verified.mapNotNull { result ->
            (result["observed"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBigDecimalOrNull()
        }
        val operation = (claim["operation"] as? JsonPrimitive)?.contentOrNull
        val value = if (inputs.size in 1..64 && verified.all { it["status"] == JsonPrimitive("VERIFIED") } && numbers.size == inputs.size) {
            when (operation) {
                "sum" -> numbers.fold(BigDecimal.ZERO, BigDecimal::add)
                "difference" -> if (numbers.size == 2) numbers[0] - numbers[1] else null
                "ratio" -> if (numbers.size == 2 && numbers[1].signum() != 0) numbers[0].divide(numbers[1], MathContext.DECIMAL64) else null
                "min" -> numbers.minOrNull()
                "max" -> numbers.maxOrNull()
                else -> null
            }
        } else {
            null
        }
        return buildJsonObject {
            put("status", if (value != null) "VERIFIED" else "NOT_MEASURED")
            put("kind", "calculation")
            put("operation", operation.orEmpty())
            put("source_count", inputs.size)
            put("sources", JsonArray(verified))
            value?.let { put("observed", JsonPrimitive(it.stripTrailingZeros())) }
            put("scope", "Only the explicitly cited observations; no estimate or inference about omitted results")
        }
    }
}
