package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()
private fun identity(value: String) = clean(value).trimEnd(':').lowercase(Locale.ROOT)

internal fun amazonProductDescription(product: JsonObject): String? = (product["description"] as? JsonPrimitive)?.contentOrNull
    ?.takeIf { it != "null" }?.take(4000)?.let(::clean)?.takeIf { it.isNotBlank() && identity(it) != identity(AmazonProducts.text(product, "title").orEmpty()) }

internal fun amazonProductHighlights(product: JsonObject): List<String> {
    val repeated = listOfNotNull(AmazonProducts.text(product, "title"), amazonProductDescription(product)).map(::identity)
    return (product["features"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>()
        .filter { it.isString }.map { clean(it.content.take(500)).trimStart('•', '-', ' ') }
        .filter { it.isNotBlank() && identity(it) !in repeated }.distinctBy(::identity).take(12)
}

internal fun amazonProductFacts(product: JsonObject): List<Pair<String, String>> {
    val labels = linkedMapOf(
        "brand" to "Brand", "model" to "Model", "color" to "Colour", "size" to "Size", "material" to "Material",
        "dimensions" to "Dimensions", "weight" to "Weight", "variant" to "Variant", "condition" to "Condition",
        "availability" to "Availability", "seller" to "Sold by", "coupon" to "Coupon"
    )
    val supplied = labels.mapNotNull { (key, label) -> AmazonProducts.text(product, key)?.let { label to clean(it) } }
    val extra = (product["specifications"] as? JsonObject).orEmpty().entries.take(24).mapNotNull { (key, raw) ->
        val value = (raw as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" && it.isNotBlank() } ?: return@mapNotNull null
        val label = clean(key.take(80)).trimEnd(':').replace(Regex("[_-]+"), " ").replaceFirstChar { it.uppercase() }
        if (label.isBlank() || identity(label) in setOf("asin", "title", "price", "currency", "marketplace", "rating", "reviews")) return@mapNotNull null
        (if (label.equals("Color", true)) "Colour" else label) to clean(value.take(500))
    }
    return (supplied + extra).distinctBy { identity(it.first) }.take(24)
}
