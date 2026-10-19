package dev.chungjungsoo.gptmobile.data.universalmarketplace

import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Native baseline: deterministic local actions, zero provider requests and zero AI calls. */
class UniversalMarketplaceCoordinator {
    suspend fun search(
        input: MarketplaceSearchRequest,
        settings: UniversalMarketplaceSettings,
        permitted: suspend () -> Boolean,
        requestId: String = UUID.randomUUID().toString()
    ): MarketplaceSearchResult {
        val request = input.normalized()
        val enabled = settings.normalized().enabledSources
        val statuses = mutableListOf<MarketplaceProviderStatus>()
        val actions = mutableListOf<MarketplaceSearchAction>()
        for (provider in request.providers) {
            coroutineContext.ensureActive()
            check(permitted()) { "Marketplace permission changed. Enable the plugin and try again." }
            when {
                provider !in enabled -> statuses += MarketplaceProviderStatus(provider, "disabled", message = "This source is disabled in Marketplace settings.")
                request.country !in MarketplaceContract.capability(provider).regions -> statuses += MarketplaceProviderStatus(provider, "unsupported_region", message = "This adapter does not support the selected country.")
                else -> {
                    val action = searchAction(provider, request)
                    actions += action
                    statuses += MarketplaceProviderStatus(provider, "link_ready", message = "Search action ready. Automatic retrieval is not configured.")
                }
            }
        }
        check(permitted()) { "Marketplace permission changed before delivery." }
        return MarketplaceSearchResult(
            requestId = requestId,
            request = request,
            outcome = if (actions.isEmpty()) "unavailable" else "links_only",
            searchActions = actions,
            providerStatuses = statuses,
            coverage = MarketplaceCoverage(request.providers, linkOnly = actions.map { it.provider }, unsuccessful = statuses.filter { it.state != "link_ready" }.map { it.provider }),
            capabilities = request.providers.map(MarketplaceContract::capability)
        ).validated()
    }

    private fun searchAction(provider: String, request: MarketplaceSearchRequest): MarketplaceSearchAction {
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val pending = linkedMapOf("country" to request.country, "currency" to request.currency)
        if (request.city.isNotBlank()) pending["city"] = request.city
        request.radiusKm?.let { pending["radius_km"] = it.toString() }
        request.maxPriceMinor?.let {
            pending["maximum_price"] = MarketplaceMoney(it, request.currency).display()
            pending["price_basis"] = request.priceBasis
        }
        if (request.fulfilment != "any") pending["fulfilment"] = request.fulfilment
        if (request.condition != "any") pending["condition"] = request.condition
        if (request.sort != "relevance") pending["sort"] = request.sort
        val url = when (provider) {
            "facebook" -> "https://www.facebook.com/marketplace/search/?query=${encode(request.query)}"
            "ebay" -> "https://www.ebay.${if (request.country == "CA") "ca" else "com"}/sch/i.html?_nkw=${encode(request.query)}"
            "craigslist" -> {
                val region = regions[request.country to request.city.lowercase(java.util.Locale.ROOT)]
                if (region == null) {
                    pending["query"] = request.query
                    "https://www.craigslist.org/about/sites"
                } else {
                    "https://$region.craigslist.org/search/sss?query=${encode(request.query)}"
                }
            }
            "kijiji" -> "https://www.kijiji.ca/b-canada/${encode(request.query)}/k0l0"
            else -> error("Unsupported provider")
        }
        check(MarketplaceContract.safeUrl(provider, url))
        return MarketplaceSearchAction(provider, url, "Open ${MarketplaceContract.names.getValue(provider)}", pending)
    }

    private companion object {
        val regions = mapOf(
            ("CA" to "toronto") to "toronto", ("CA" to "vancouver") to "vancouver", ("CA" to "montreal") to "montreal",
            ("CA" to "ottawa") to "ottawa", ("CA" to "calgary") to "calgary", ("US" to "new york") to "newyork",
            ("US" to "san francisco") to "sfbay", ("US" to "los angeles") to "losangeles", ("US" to "seattle") to "seattle"
        )
    }
}
