package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Retail search has its own contract; web-source compaction must not discard product facts. */
object AmazonProducts {
    const val SCHEMA = "amazon_products_v1"
    val marketplaces = linkedMapOf(
        "amazon.ca" to "Canada", "amazon.com" to "United States", "amazon.co.uk" to "United Kingdom",
        "amazon.com.au" to "Australia", "amazon.de" to "Germany", "amazon.fr" to "France",
        "amazon.it" to "Italy", "amazon.es" to "Spain", "amazon.co.jp" to "Japan",
        "amazon.in" to "India", "amazon.com.br" to "Brazil", "amazon.com.mx" to "Mexico",
        "amazon.sg" to "Singapore", "amazon.nl" to "Netherlands", "amazon.com.be" to "Belgium",
        "amazon.pl" to "Poland", "amazon.se" to "Sweden", "amazon.ae" to "United Arab Emirates",
        "amazon.sa" to "Saudi Arabia", "amazon.com.tr" to "Turkey", "amazon.eg" to "Egypt"
    )
    private val unavailablePrice = Regex("(?i)unavailable|indisponible|not available|unknown|n/a|%|save")
    private val asinPattern = Regex("[A-Z0-9]{10}")
    private val asinPath = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})(?:/|$)", RegexOption.IGNORE_CASE)

    fun marketplace(value: String): String? = value.trim().lowercase(Locale.ROOT).removePrefix("www.").takeIf { it in marketplaces }
    fun asin(value: String): String? = value.trim().uppercase(Locale.ROOT).takeIf(asinPattern::matches)

    fun marketplaceFromUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) null else marketplace(uri.host.orEmpty())
    }.getOrNull()

    fun productUrl(domain: String, id: String): String? = marketplace(domain)?.let { market ->
        asin(id)?.let { "https://www.$market/dp/$it" }
    }

    /** Keep explicit host-configured affiliate tags on the matching product page only. */
    fun affiliateUrl(value: String, domain: String, id: String): String? = runCatching {
        val uri = URI(value)
        val canonical = productUrl(domain, id) ?: return null
        if (marketplaceFromUrl(value) != marketplace(domain) || !uri.path.trimEnd('/').equals("/dp/${asin(id)}", true) || uri.fragment != null) return null
        val query = uri.rawQuery.orEmpty().split('&').map { part ->
            val pair = part.split('=', limit = 2)
            java.net.URLDecoder.decode(pair.first(), "UTF-8") to java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        if (query.any { it.first !in setOf("tag", "linkCode") } || query.count { it.first == "tag" } != 1 || query.count { it.first == "linkCode" } > 1) return null
        val tag = query.single { it.first == "tag" }.second
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(tag) || query.any { it.first == "linkCode" && it.second != "ll1" }) return null
        "$canonical?tag=$tag&linkCode=ll1"
    }.getOrNull()

    fun imageUrl(value: String?): String? = value?.takeIf { link ->
        runCatching {
            val uri = URI(link)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            uri.scheme == "https" &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.query == null &&
                listOf("media-amazon.com", "images-amazon.com", "ssl-images-amazon.com").any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
    }

    /** Providers return either a URL, an image object or an array of gallery images. */
    fun productImageUrl(product: JsonObject): String? = productImageUrls(product).firstOrNull()

    fun productImageUrls(product: JsonObject): List<String> {
        fun find(value: JsonElement?, depth: Int = 0): List<String> {
            if (depth > 5) return emptyList()
            return when (value) {
                is JsonPrimitive -> listOfNotNull(imageUrl(value.contentOrNull))
                is JsonObject -> listOf("hiRes", "hi_res", "large", "link", "url", "src", "imageUrl", "image_url", "thumbnail")
                    .flatMap { find(value[it], depth + 1) } + value.keys.mapNotNull(::imageUrl)
                is JsonArray -> value.take(20).flatMap { find(it, depth + 1) }
                else -> emptyList()
            }
        }
        return listOf("imageUrl", "thumbnail", "image", "image_url", "main_image", "mainImage", "images", "thumbnails", "image_urls", "imageUrls", "gallery", "colorImages")
            .flatMap { find(product[it]) }.distinct().take(8)
    }

    fun text(value: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (value[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" && it.isNotBlank() }?.take(500)
    }

    fun hasPrice(product: JsonObject): Boolean {
        if (decimal(product["priceAmount"]) != null) return true
        val display = text(product, "price") ?: return false
        return display.any(Char::isDigit) && !unavailablePrice.containsMatchIn(display)
    }

    /** Enrich the same product without replacing the card's offer, price, currency or affiliate link. */
    fun withDetails(product: JsonObject, details: JsonObject): JsonObject {
        if (text(product, "marketplace") != text(details, "marketplace") || text(product, "asin") != text(details, "asin")) return product
        val variant = text(product, "variant")
        val detailedVariant = text(details, "variant")
        if (variant != null && detailedVariant != null && !variant.equals(detailedVariant, true)) return product
        val fields = details.filter { (key, value) ->
            key in setOf("description", "features", "imageUrl", "brand", "model", "color", "size", "material", "dimensions", "weight", "specifications") &&
                value != kotlinx.serialization.json.JsonNull &&
                value.toString() !in setOf("\"\"", "[]", "{}")
        }
        val photos = (productImageUrls(details) + productImageUrls(product)).distinct().take(8)
        return JsonObject(product + fields + if (photos.isNotEmpty()) mapOf("imageUrl" to JsonPrimitive(photos.first()), "images" to JsonArray(photos.map(::JsonPrimitive))) else emptyMap())
    }

    private fun decimal(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
        if (raw.length > 40) return@let null
        runCatching { BigDecimal(raw).takeIf { it.scale() in -12..12 && it.signum() >= 0 && it <= BigDecimal("1000000000") }?.stripTrailingZeros()?.toPlainString() }.getOrNull()
    }

    fun unwrap(payload: JsonElement, depth: Int = 0): JsonElement {
        if (depth > 6) return payload
        return when (payload) {
            is JsonObject -> if (listOf("organic_results", "product_results", "asin", "ASIN", "error").any { it in payload }) {
                payload
            } else {
                listOf("result", "structuredContent", "data", "text", "content").firstNotNullOfOrNull { payload[it] }?.let { unwrap(it, depth + 1) } ?: payload
            }
            is JsonArray -> if (payload.size == 1) unwrap(payload.single(), depth + 1) else payload
            is JsonPrimitive -> if (payload.isString && payload.content.length <= 1_000_000) {
                runCatching { Json.parseToJsonElement(payload.content) }.getOrNull()?.let { unwrap(it, depth + 1) } ?: payload
            } else {
                payload
            }
            else -> payload
        }
    }

    fun observedAt(payload: JsonElement): String? {
        val metadata = (payload as? JsonObject)?.get("search_metadata") as? JsonObject ?: return null
        val raw = text(metadata, "created_at") ?: return null
        return runCatching { Instant.parse(raw).toString() }.getOrNull() ?: runCatching {
            LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)).toInstant(ZoneOffset.UTC).toString()
        }.getOrNull()
    }

    /** Keep the retail contract valid when the user chooses a small output limit. */
    fun limitResult(payload: JsonObject, maxCharacters: Int): JsonObject {
        if (payload.toString().length <= maxCharacters) return payload
        fun compactProduct(value: JsonElement): JsonElement {
            val product = value as? JsonObject ?: return value
            return JsonObject(
                product.filterKeys { it !in setOf("description", "features", "imageUrl") }.mapValues { (key, field) ->
                    if (key == "title" && field is JsonPrimitive && field.isString) JsonPrimitive(field.content.take(120)) else field
                }
            )
        }
        val products = (payload["products"] as? JsonArray).orEmpty().map(::compactProduct).toMutableList()
        val unverified = (payload["unverifiedProducts"] as? JsonArray).orEmpty().map(::compactProduct).toMutableList()
        var base = payload.filterKeys { it in setOf("schema", "status", "requestId", "marketplace", "provider", "page", "coverage", "preview", "observedAt", "retrievedAt", "cached", "errors", "partialErrors", "filterNotice") } + mapOf(
            "outputLimited" to JsonPrimitive(true),
            "notice" to JsonPrimitive("Output limited. Narrow the request or increase the limit. Prices may change at checkout.")
        )
        while (true) {
            val bounded = JsonObject(base + ("products" to JsonArray(products)) + if (unverified.isNotEmpty()) mapOf("unverifiedProducts" to JsonArray(unverified)) else emptyMap())
            if (bounded.toString().length <= maxCharacters) return bounded
            if (unverified.isNotEmpty()) {
                unverified.removeAt(unverified.lastIndex)
                continue
            }
            if (products.isNotEmpty()) {
                products.removeAt(products.lastIndex)
                continue
            }
            val errors = base["errors"] as? JsonArray
            val partial = base["partialErrors"] as? JsonArray
            if (errors != null && errors.size > 1) {
                base = base + ("errors" to JsonArray(errors.take(1))) + ("omittedErrors" to JsonPrimitive(errors.size - 1))
                continue
            }
            if (partial != null && partial.size > 1) {
                base = base + ("partialErrors" to JsonArray(partial.take(1)))
                continue
            }
            // Runtime plugin limits are at least 1,000 chars. Keep a typed explanation even for large upstream messages.
            val firstError = errors?.firstOrNull() as? JsonObject
            val compact = JsonObject(
                base.filterKeys { it in setOf("schema", "status", "requestId", "marketplace", "provider", "page", "preview", "coverage") } + mapOf(
                    "products" to JsonArray(emptyList()),
                    "outputLimited" to JsonPrimitive(true),
                    "errors" to JsonArray(
                        listOf(
                            buildJsonObject {
                                put("code", firstError?.let { text(it, "code") } ?: "OUTPUT_LIMITED")
                                put("message", (firstError?.let { text(it, "message") } ?: "Product facts exceeded the output limit; narrow the request.").take(160))
                            }
                        )
                    )
                )
            )
            return compact
        }
    }

    fun normalize(
        payload: JsonElement,
        domain: String,
        provider: String,
        clock: Clock = Clock.systemUTC(),
        includeSponsored: Boolean = false,
        maxResults: Int = 10
    ): List<JsonObject> {
        val market = marketplace(domain) ?: return emptyList()
        val observed = observedAt(unwrap(payload))
        val retrieved = clock.instant().toString()
        return candidates(payload).mapNotNull { item ->
            val id = text(item, "asin", "ASIN")?.let(::asin) ?: text(item, "url", "link")?.let { link ->
                if (marketplaceFromUrl(link) != market) null else asinPath.find(URI(link).path.orEmpty())?.groupValues?.get(1)?.let(::asin)
            } ?: return@mapNotNull null
            // An explicit different marketplace must never be relabeled as the requested one.
            if (text(item, "marketplace")?.let { marketplace(it) != market } == true) return@mapNotNull null
            val sourceUrl = text(item, "url", "link", "link_clean")
            if (sourceUrl != null && marketplaceFromUrl(sourceUrl) != market) return@mapNotNull null
            val title = text(item, "title", "name", "product_title", "product_name") ?: return@mapNotNull null
            val sponsored = (item["sponsored"] as? JsonPrimitive)?.booleanOrNull
                ?: (item["is_sponsored"] as? JsonPrimitive)?.booleanOrNull
            if (!includeSponsored && sponsored == true) return@mapNotNull null
            val priceObject = item["price"] as? JsonObject
            val amount = decimal(item["extracted_price"] ?: item["final_price"] ?: priceObject?.get("value") ?: item["price"])
            buildJsonObject {
                put("asin", id)
                put("marketplace", market)
                put("title", title.take(300))
                put("url", requireNotNull(productUrl(market, id)))
                put("provider", provider)
                ((item["description"] ?: item["product_description"]) as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" && it.isNotBlank() }?.let { put("description", it.take(4000)) }
                ((item["features"] ?: item["feature_bullets"]) as? JsonArray)?.let { put("features", JsonArray(it.take(12))) }
                put("retrievedAt", retrieved)
                observed?.let { put("observedAt", it) }
                val photos = productImageUrls(item)
                photos.firstOrNull()?.let { put("imageUrl", it) }
                if (photos.size > 1) put("images", JsonArray(photos.map(::JsonPrimitive)))
                mapOf("brand" to listOf("brand", "manufacturer"), "model" to listOf("model", "model_number"), "color" to listOf("color", "colour"), "size" to listOf("size"), "material" to listOf("material"), "dimensions" to listOf("dimensions", "product_dimensions"), "weight" to listOf("weight", "item_weight")).forEach { (key, aliases) ->
                    text(item, *aliases.toTypedArray())?.let { put(key, it) }
                }
                ((item["specifications"] ?: item["product_information"]) as? JsonObject)?.let { specifications ->
                    put(
                        "specifications",
                        JsonObject(
                            specifications.entries.take(24).mapNotNull { (key, value) ->
                                (value as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" && it.isNotBlank() }?.let { key.take(80) to JsonPrimitive(it.take(500)) }
                            }.toMap()
                        )
                    )
                }
                (text(item, "price", "price_string") ?: priceObject?.let { text(it, "raw", "display") } ?: amount)?.let { put("price", it) }
                amount?.let { put("priceAmount", it) }
                decimal(item["extracted_max_price"])?.let { put("priceMaxAmount", it) }
                (text(item, "currency") ?: priceObject?.let { text(it, "currency") })?.takeIf { Regex("[A-Z]{3}").matches(it) }?.let { put("currency", it) }
                ((item["rating"] ?: item["stars"]) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..5.0 }?.let { put("rating", it) }
                ((item["reviews"] ?: item["reviews_count"] ?: item["total_reviews"]) as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }?.let { put("reviewCount", it) }
                text(item, "seller", "seller_name", "sold_by")?.let { put("seller", it) }
                text(item, "condition")?.let { put("condition", it) }
                text(item, "variant", "selected_variant")?.let { put("variant", it) }
                text(item, "stock", "availability")?.let { put("availability", it) }
                text(item, "save_with_coupon")?.let { put("coupon", it) }
                ((item["prime"] ?: item["is_prime"]) as? JsonPrimitive)?.booleanOrNull?.let { put("prime", it) }
                sponsored?.let { put("sponsored", it) }
            }
        }.distinctBy { listOf(text(it, "marketplace"), text(it, "asin"), text(it, "seller"), text(it, "condition"), text(it, "variant")) }
            .take(maxResults.coerceIn(1, 100))
    }

    private fun candidates(value: JsonElement, depth: Int = 0): List<JsonObject> {
        if (depth > 6) return emptyList()
        return when (value) {
            is JsonArray -> value.take(100).flatMap { candidates(it, depth + 1) }.take(100)
            is JsonObject -> {
                if (text(value, "asin", "ASIN") != null || text(value, "link", "url")?.let { asinPath.containsMatchIn(it) } == true) {
                    listOf(value)
                } else {
                    listOf("organic_results", "product_results", "products", "results", "search_results", "data", "result", "structuredContent", "content", "text")
                        .flatMap { value[it]?.let { child -> candidates(child, depth + 1) }.orEmpty() }
                }
            }
            is JsonPrimitive -> if (value.isString && value.content.length <= 1_000_000) {
                runCatching { Json.parseToJsonElement(value.content) }.getOrNull()?.let { candidates(it, depth + 1) }.orEmpty()
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }
}
