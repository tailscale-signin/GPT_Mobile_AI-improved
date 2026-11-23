package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A source may be a private contract note; generic URL credential redaction is insufficient. */
internal fun redactBenchmarkPricingSnapshot(value: JsonObject, includePrivateProfileDetails: Boolean = false): JsonObject {
    val legacy = value["price"] as? JsonObject
    val quote = value["quote"] as? JsonObject
    val redactedSource = "[redacted price source]"
    fun sourceText(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it != redactedSource }
    val source = if (quote != null) sourceText(quote["sourceUrl"]) else sourceText(legacy?.get("source"))
    val digest = source?.let(::benchmarkHash) ?: (value["sourceDigest"] as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    val kind = (quote?.get("source") as? JsonPrimitive)?.content?.takeIf { candidate -> PriceSource.entries.any { it.name == candidate } }
        ?: "LEGACY_ALLOWANCE_RATE".takeIf { legacy != null }
    val fields = value.toMutableMap()
    fields["sourceKind"] = kind?.let(::JsonPrimitive) ?: JsonNull
    fields["sourceDigest"] = digest?.let(::JsonPrimitive) ?: JsonNull
    if (!includePrivateProfileDetails) {
        if (legacy != null) fields["price"] = JsonObject(legacy + ("source" to JsonPrimitive(redactedSource)))
        if (quote != null) fields["quote"] = JsonObject(quote + ("sourceUrl" to JsonPrimitive("")))
    }
    return JsonObject(fields)
}
