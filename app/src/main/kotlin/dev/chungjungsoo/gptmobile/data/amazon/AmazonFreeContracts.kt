package dev.chungjungsoo.gptmobile.data.amazon

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** These markets are a preview, pending the live reliability gate in the implementation plan. */
enum class AmazonFreeMarket(val domain: String, val label: String, val currency: String, val language: String) {
    CANADA("amazon.ca", "Canada", "CAD", "en-CA"),
    UNITED_STATES("amazon.com", "United States", "USD", "en-US");

    companion object {
        fun fromDomain(domain: String): AmazonFreeMarket? = entries.firstOrNull { it.domain == domain }
    }
}

data class AmazonSearchRequest(
    val query: String,
    val marketplace: AmazonFreeMarket,
    val maxResults: Int = 10,
    val includeSponsored: Boolean = false,
    val sort: String = "source",
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null
)

data class AmazonProductRequest(val asins: List<String>, val marketplace: AmazonFreeMarket)

data class AmazonProductObservation(
    val asin: String,
    val marketplace: AmazonFreeMarket,
    val title: String,
    val acquiredAt: Instant,
    val sourceType: String,
    val price: String? = null,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val imageUrl: String? = null,
    val sponsored: Boolean? = null,
    val prime: Boolean? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("asin", asin)
        put("marketplace", marketplace.domain)
        put("title", title)
        put("url", requireNotNull(AmazonProducts.productUrl(marketplace.domain, asin)))
        put("provider", "free_native")
        put("retrievedAt", acquiredAt.toString())
        put("observedAt", acquiredAt.toString())
        put("sourceType", sourceType)
        put("priceBasis", "base_item")
        put("offerContextComplete", false)
        put("cached", false)
        price?.let { put("price", it) }
        amount?.let { put("priceAmount", it.toPlainString()) }
        currency?.let { put("currency", it) }
        rating?.let { put("rating", it) }
        reviewCount?.let { put("reviewCount", it) }
        imageUrl?.let { put("imageUrl", it) }
        sponsored?.let { put("sponsored", it) }
        prime?.let { put("prime", it) }
    }
}

enum class AmazonReadError {
    INVALID_ARGUMENT,
    UNSUPPORTED_MARKETPLACE,
    PLUGIN_DISABLED,
    CHALLENGE_REQUIRED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    COOLDOWN_ACTIVE,
    PRICE_UNAVAILABLE,
    PARSE_CHANGED,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    NETWORK_ERROR
}

class AmazonReadException(val code: AmazonReadError, message: String, val retryAfterMillis: Long? = null) : Exception(message)

data class AmazonItemFailure(val code: AmazonReadError, val message: String, val asin: String? = null) {
    fun toJson() = buildJsonObject {
        put("code", code.name)
        put("message", message)
        asin?.let { put("asin", it) }
    }
}

data class AmazonFetchResult(
    val products: List<AmazonProductObservation>,
    val errors: List<AmazonItemFailure> = emptyList(),
    val pagesFetched: Int = 1
) {
    fun toJson(requestId: String, market: AmazonFreeMarket): JsonObject = buildJsonObject {
        put("schema", AmazonProducts.SCHEMA)
        put(
            "status",
            if (errors.isEmpty()) {
                "success"
            } else if (products.isEmpty()) {
                "failure"
            } else {
                "partial"
            }
        )
        put("requestId", requestId.take(100))
        put("provider", "free_native")
        put("marketplace", market.domain)
        put("preview", true)
        put(
            "coverage",
            buildJsonObject {
                put("pagesFetched", pagesFetched)
                put("exhaustive", false)
            }
        )
        put("products", JsonArray(products.map(AmazonProductObservation::toJson)))
        put("errors", JsonArray(errors.take(10).map(AmazonItemFailure::toJson)))
        put("notice", "Public-page preview. Prices and availability may change at checkout. Shipping, tax, coupons and offer identity are not confirmed. Sorting and price filters apply only to the retrieved page. No historical prices or alerts are supplied by this provider yet.")
    }
}
