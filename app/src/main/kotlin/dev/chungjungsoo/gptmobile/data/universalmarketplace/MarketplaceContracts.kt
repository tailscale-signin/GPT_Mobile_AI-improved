package dev.chungjungsoo.gptmobile.data.universalmarketplace

import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class UniversalMarketplaceSettings(
    val enabledSources: List<String> = MarketplaceContract.providers,
    val country: String = "CA",
    val city: String = "",
    val currency: String = "CAD",
    val maxResults: Int = 30
) {
    fun normalized(): UniversalMarketplaceSettings {
        require(country in MarketplaceContract.countries && currency in MarketplaceContract.currencies) { "Choose a supported country and currency." }
        require(city.length <= 100 && city.none(Char::isISOControl)) { "City must be at most 100 characters without control characters." }
        require(enabledSources.all { it in MarketplaceContract.providers }) { "Unknown marketplace source." }
        require(maxResults in 1..100) { "Result preference must be 1–100." }
        return copy(enabledSources = enabledSources.distinct(), city = city.trim())
    }
}

@Serializable
data class MarketplaceSearchRequest(
    val query: String,
    val providers: List<String>,
    val country: String,
    val city: String,
    val currency: String,
    @SerialName("radius_km") val radiusKm: Int? = null,
    @SerialName("max_price_minor") val maxPriceMinor: Long? = null,
    @SerialName("price_basis") val priceBasis: String = "item",
    val fulfilment: String = "any",
    val condition: String = "any",
    val sort: String = "relevance",
    @SerialName("max_results") val maxResults: Int = 30,
    val cursor: String? = null
) {
    fun normalized(): MarketplaceSearchRequest {
        val text = query.trim()
        require(text.length in 1..250 && query.none(Char::isISOControl)) { "Query must contain 1–250 characters without control characters." }
        require(providers.isNotEmpty() && providers.all { it in MarketplaceContract.providers }) { "Select recognized marketplace sources." }
        require(country in MarketplaceContract.countries && currency in MarketplaceContract.currencies) { "Choose a supported country and currency." }
        require(city.length <= 100 && city.none(Char::isISOControl)) { "Invalid city." }
        require(radiusKm == null || radiusKm in 0..1000) { "Radius must be 0–1000 km." }
        require(maxPriceMinor == null || maxPriceMinor >= 0) { "Price must be nonnegative integer minor units." }
        require(priceBasis in setOf("item", "item_plus_shipping")) { "Unknown price basis." }
        require(fulfilment in setOf("any", "pickup", "shipping")) { "Unknown fulfilment." }
        require(condition in setOf("any", "new", "used", "refurbished")) { "Unknown condition." }
        require(sort in setOf("relevance", "price_asc", "price_desc", "newest")) { "Unknown sort." }
        require(maxResults in 1..100) { "Result limit must be 1–100." }
        require(cursor == null) { "INVALID_CURSOR: link-only sources have no retrieved result pages." }
        return copy(query = text, providers = providers.distinct().sorted(), city = city.trim())
    }

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(normalized()).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

@Serializable
data class MarketplaceMoney(val minor: Long, val currency: String) {
    init {
        require(minor >= 0 && currency in MarketplaceContract.currencies)
    }
    fun display(): String = "$currency ${BigDecimal.valueOf(minor, 2).toPlainString()}"
    fun plus(shipping: MarketplaceMoney?): MarketplaceMoney? = shipping?.takeIf { it.currency == currency }?.let { runCatching { copy(minor = Math.addExact(minor, it.minor)) }.getOrNull() }
}

@Serializable
data class MarketplaceListing(
    @SerialName("provider_id") val providerId: String,
    @SerialName("listing_id") val listingId: String,
    val url: String,
    val title: String,
    val item: MarketplaceMoney? = null,
    val shipping: MarketplaceMoney? = null,
    val condition: String = "unknown",
    val fulfilment: String = "unknown",
    @SerialName("observed_at") val observedAt: Long,
    @SerialName("source_mode") val sourceMode: String,
    val availability: String = "unknown"
)

@Serializable
data class MarketplaceDiscovery(val url: String, val title: String, val snippet: String, val engine: String, @SerialName("retrieved_at") val retrievedAt: Long, @SerialName("filter_status") val filterStatus: String = "unknown")

@Serializable
data class MarketplaceSearchAction(
    val provider: String,
    val url: String,
    val label: String,
    @SerialName("pending_filters") val pendingFilters: Map<String, String>,
    val notice: String = "Open the site and confirm the search and filters. No listings have been retrieved."
)

@Serializable
data class MarketplaceProviderStatus(val provider: String, val state: String, val mode: String = "external_search", val count: Int = 0, val message: String = "")

@Serializable
data class MarketplaceCoverage(val requested: List<String>, @SerialName("automatically_queried") val automaticallyQueried: List<String> = emptyList(), @SerialName("link_only") val linkOnly: List<String>, val unsuccessful: List<String>, val exhaustive: Boolean = false)

/** Reviewed compiled behavior. Metadata cannot upgrade an external link into retrieval. */
@Serializable
data class MarketplaceSourceCapability(
    val provider: String,
    val version: Int = 1,
    @SerialName("access_mode") val accessMode: String = "external_search",
    val regions: List<String>,
    @SerialName("supported_filters") val supportedFilters: List<String> = emptyList(),
    @SerialName("authentication_required") val authenticationRequired: Boolean = false,
    @SerialName("permitted_operations") val permittedOperations: List<String> = listOf("prepare_search"),
    val pagination: Boolean = false,
    val ranking: String = "none",
    @SerialName("display_requirement") val displayRequirement: String = "External search; no retrieved listings. Confirm pending filters on site.",
    @SerialName("cache_policy") val cachePolicy: String = "no_third_party_content",
    @SerialName("monitoring_allowed") val monitoringAllowed: Boolean = false
)

@Serializable
data class MarketplaceSearchResult(
    val schema: String = MarketplaceContract.SCHEMA,
    @SerialName("request_id") val requestId: String,
    val request: MarketplaceSearchRequest,
    val outcome: String,
    val listings: List<MarketplaceListing> = emptyList(),
    val discoveries: List<MarketplaceDiscovery> = emptyList(),
    @SerialName("search_actions") val searchActions: List<MarketplaceSearchAction>,
    @SerialName("provider_statuses") val providerStatuses: List<MarketplaceProviderStatus>,
    val coverage: MarketplaceCoverage,
    @SerialName("next_cursor") val nextCursor: String? = null,
    val capabilities: List<MarketplaceSourceCapability> = emptyList()
) {
    /** Validate the whole envelope before a persisted or tool response becomes interactive. */
    fun validated(): MarketplaceSearchResult {
        require(schema == MarketplaceContract.SCHEMA && requestId.isNotBlank() && requestId.length <= 128)
        require(request == request.normalized())
        require(listings.isEmpty() && discoveries.isEmpty() && nextCursor == null)
        require(providerStatuses.map { it.provider }.sorted() == request.providers.sorted())
        require(providerStatuses.all { it.count == 0 && it.mode == "external_search" && it.state in setOf("link_ready", "disabled", "unsupported_region") })
        val linked = providerStatuses.filter { it.state == "link_ready" }.map { it.provider }.sorted()
        require(searchActions.map { it.provider }.sorted() == linked)
        require(searchActions.all { MarketplaceContract.safeUrl(it.provider, it.url) })
        require(outcome == if (linked.isEmpty()) "unavailable" else "links_only")
        require(coverage.requested == request.providers && coverage.automaticallyQueried.isEmpty() && !coverage.exhaustive)
        require(coverage.linkOnly.sorted() == linked)
        require(coverage.unsuccessful.sorted() == providerStatuses.filter { it.state != "link_ready" }.map { it.provider }.sorted())
        require(capabilities.isEmpty() || capabilities == request.providers.map(MarketplaceContract::capability))
        return this
    }
}

object MarketplaceContract {
    const val PROVIDER = "universal-marketplace"
    const val INSTALLATION = "optional-universal-marketplace"
    const val TOOL = "universal_marketplace__search"
    const val SCHEMA = "gptmobile.universal_marketplace.v1"
    val providers = listOf("facebook", "ebay", "craigslist", "kijiji")
    val countries = listOf("CA", "US")
    val currencies = listOf("CAD", "USD")
    val names = mapOf("facebook" to "Facebook Marketplace", "ebay" to "eBay", "craigslist" to "Craigslist", "kijiji" to "Kijiji")
    val json = Json { encodeDefaults = true }

    fun capability(provider: String): MarketplaceSourceCapability {
        require(provider in providers)
        return MarketplaceSourceCapability(provider, regions = if (provider == "kijiji") listOf("CA") else countries)
    }

    fun decode(arguments: JsonObject, settings: UniversalMarketplaceSettings = UniversalMarketplaceSettings()): MarketplaceSearchRequest {
        // kotlinx.serialization accepts quoted numbers for numeric properties. The tool
        // contract does not: prices must have an exact JSON integer representation.
        for (key in listOf("radius_km", "max_price_minor", "max_results")) {
            val value = arguments[key] ?: continue
            require(value is JsonPrimitive && !value.isString && value.longOrNull != null) { "$key must be a JSON integer." }
        }
        val defaults = settings.normalized()
        val base = buildJsonObject {
            put("providers", JsonArray(defaults.enabledSources.map(::JsonPrimitive)))
            put("country", defaults.country)
            put("city", defaults.city)
            put("currency", defaults.currency)
            put("max_results", defaults.maxResults)
        }
        return json.decodeFromJsonElement(MarketplaceSearchRequest.serializer(), JsonObject(base + arguments)).normalized()
    }

    fun safeUrl(provider: String, value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val domains = when (provider) {
            "facebook" -> setOf("www.facebook.com", "facebook.com")
            "ebay" -> setOf("www.ebay.ca", "www.ebay.com", "ebay.ca", "ebay.com")
            "craigslist" -> setOf("www.craigslist.org", "craigslist.org", "toronto.craigslist.org", "vancouver.craigslist.org", "montreal.craigslist.org", "ottawa.craigslist.org", "calgary.craigslist.org", "newyork.craigslist.org", "sfbay.craigslist.org", "losangeles.craigslist.org", "seattle.craigslist.org")
            "kijiji" -> setOf("www.kijiji.ca", "kijiji.ca")
            else -> emptySet()
        }
        value.length <= 4096 && value.none(Char::isISOControl) && uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.userInfo == null && host in domains
    }.getOrDefault(false)

    val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
        put(
            "properties",
            buildJsonObject {
                fun text(name: String, values: List<String>? = null, max: Int = 100) {
                    put(
                        name,
                        buildJsonObject {
                            put("type", "string")
                            put("maxLength", max)
                            values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
                        }
                    )
                }
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 250)
                    }
                )
                put(
                    "providers",
                    buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 4)
                        put("uniqueItems", true)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(providers.map(::JsonPrimitive)))
                            }
                        )
                    }
                )
                text("country", countries)
                text("currency", currencies)
                text("city")
                text("price_basis", listOf("item", "item_plus_shipping"))
                text("fulfilment", listOf("any", "pickup", "shipping"))
                text("condition", listOf("any", "new", "used", "refurbished"))
                text("sort", listOf("relevance", "price_asc", "price_desc", "newest"))
                put(
                    "radius_km",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", 1000)
                    }
                )
                put(
                    "max_price_minor",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", Long.MAX_VALUE)
                    }
                )
                put(
                    "max_results",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 100)
                    }
                )
                put(
                    "cursor",
                    buildJsonObject {
                        put("type", "null")
                        put("default", JsonNull)
                    }
                )
            }
        )
    }
}
