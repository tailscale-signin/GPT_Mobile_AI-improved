package dev.chungjungsoo.gptmobile.data.catalog

/** A downloadable connection package is not an Android executable plugin. */
enum class MarketplaceRuntime(val label: String) {
    COMPANION("Computer-hosted MCP"),
    HOSTED("Hosted MCP"),
    GUIDE("Setup guide only")
}

data class GitHubMarketplacePackage(
    val provider: String,
    val preset: McpPreset,
    val runtime: MarketplaceRuntime,
    val credentialVariable: String = "",
    val serviceNotice: String
) {
    val id: String get() = preset.id
    val canConnect: Boolean get() = runtime != MarketplaceRuntime.GUIDE
}

enum class MarketplaceSection(val label: String) {
    PLUGINS("Integrated Plugins"),
    MCP("MCP Tools")
}

enum class MarketplaceSort(val label: String) {
    RECOMMENDED("Recommended"),
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    ADDED_FIRST("Added first")
}

object MarketplacePresentation {
    fun section(preset: McpPreset): MarketplaceSection =
        if (preset.isPreinstalled || preset.integratedTool != null || preset.commandOrUrl.startsWith("builtin://")) {
            MarketplaceSection.PLUGINS
        } else {
            MarketplaceSection.MCP
        }

    fun filterAndSort(
        presets: List<McpPreset>,
        section: MarketplaceSection,
        query: String,
        category: McpCategory?,
        pricing: McpPricingType?,
        sort: MarketplaceSort,
        addedIds: Set<String>
    ): List<McpPreset> {
        val needle = query.trim()
        val filtered = presets.filter { preset ->
            section(preset) == section &&
                (category == null || preset.category == category) &&
                (pricing == null || preset.pricing == pricing) &&
                (
                    needle.isEmpty() ||
                        (
                            listOf(
                                preset.name,
                                preset.description,
                                preset.alias,
                                preset.author,
                                preset.category.displayName
                            ) + preset.toolCapabilities
                            ).any { it.contains(needle, ignoreCase = true) }
                    )
        }
        val names = compareBy<McpPreset> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
        return when (sort) {
            MarketplaceSort.RECOMMENDED -> filtered
            MarketplaceSort.NAME_ASC -> filtered.sortedWith(names)
            MarketplaceSort.NAME_DESC -> filtered.sortedWith(names.reversed())
            MarketplaceSort.ADDED_FIRST -> filtered.sortedWith(
                compareByDescending<McpPreset> { it.isPreinstalled || it.id in addedIds }.then(names)
            )
        }
    }
}

object GitHubMarketplaceCatalog {
    const val SOURCE_REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"

    // Immutable assets. Never follow main/latest or accept a checksum supplied by the download itself.
    const val SOURCE_COMMIT = "f6e00d81946e29983f439566aa43957a86da08a5"
    const val GUIDE_SHA256 = "4dc819a557414bc1d8e2e53aeb9e06aecf3459666dc5c0a5a90dc021a008c1e1"
    const val COMPANION_SHA256 = "49cd0e7e50c7518e77dac361e7a82428157b6e12c8d0c5d8e89bbccab1b871bc"
    const val SOURCE_DIRECTORY = "https://github.com/$SOURCE_REPOSITORY/tree/$SOURCE_COMMIT/mcp/marketplace"

    private fun companion(
        provider: String,
        name: String,
        description: String,
        tools: List<String>,
        docs: String,
        credential: String = "",
        notice: String = "Provider coverage and fair-use limits apply.",
        category: McpCategory = McpCategory.SEARCH,
        pricing: McpPricingType = McpPricingType.FREE
    ) = GitHubMarketplacePackage(
        provider,
        McpPreset(
            id = "optional-$provider", name = name, description = description, category = category,
            commandOrUrl = "", alias = "places_${provider.replace('-', '_')}", iconName = "location",
            author = "GPT Mobile · companion adapter", suggestedAuthType = "BEARER", pricing = pricing,
            requiredFields = listOf("Companion MCP token (not the provider API key)"), toolCapabilities = tools,
            websiteUrl = docs,
            setupInstructions = "Download and export the package. Run its Python 3.11+ companion on your computer. " +
                "Enable $provider in MARKETPLACE_ENABLED; use your host's /mcp/$provider URL and " +
                "MARKETPLACE_MCP_TOKEN as the Bearer credential. " +
                (if (credential.isBlank()) "" else "Set $credential on the host. ") + notice
        ),
        MarketplaceRuntime.COMPANION,
        credential,
        notice
    )

    private fun guide(provider: String, name: String, docs: String, description: String) = GitHubMarketplacePackage(
        provider,
        McpPreset(
            id = "optional-$provider", name = name, description = description, category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "", alias = "guide_${provider.replace('-', '_')}", author = "GPT Mobile · integration plan",
            iconName = "folder", websiteUrl = docs, documentationOnly = true,
            setupInstructions = "Downloadable setup plan only. No executable Android plugin, MCP endpoint or offline dataset is installed."
        ),
        MarketplaceRuntime.GUIDE,
        serviceNotice = "Adapter/data pipeline not implemented in this first pass."
    )

    val packages: List<GitHubMarketplacePackage> = listOf(
        companion(
            "refuge",
            "REFUGE Restrooms",
            "Find nearby community-listed restrooms without a provider API key.",
            listOf("restrooms: nearby records with supplied access attributes"),
            "https://www.refugerestrooms.org/api/docs/"
        ),
        companion(
            "toronto",
            "Toronto Open Data",
            "Find municipal datasets, washroom resources and DineSafe records.",
            listOf("datasets: search CKAN metadata", "records: read a bounded resource page"),
            "https://open.toronto.ca/dataset/washroom-facilities/",
            notice = "No offline pack or nearest-facility ranking yet. Preserve municipal attribution and source inspection dates."
        ),
        companion(
            "nominatim",
            "OpenStreetMap · Nominatim",
            "Geocode place names through a managed or self-hosted service.",
            listOf("geocode: up to five address candidates"),
            "https://operations.osmfoundation.org/policies/nominatim/",
            "NOMINATIM_ENDPOINT",
            "Shared public Nominatim is not configured as an application backend. Hosting may cost money."
        ),
        companion(
            "overpass",
            "OpenStreetMap · Restroom Finder",
            "Search standalone toilets and explicitly tagged venue toilets.",
            listOf("restrooms: bounded 1.5 km Overpass query"),
            "https://dev.overpass-api.de/overpass-doc/en/preface/commons.html",
            "OVERPASS_ENDPOINT",
            "Use your permitted managed/self-hosted endpoint. Results are partial; OSM attribution applies."
        ),
        companion(
            "ticketmaster", "Ticketmaster Events", "Discover ticketed events by keyword and city.",
            listOf("events: first ten event matches"), "https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/",
            "TICKETMASTER_API_KEY", "Account allowance applies; not a comprehensive community-events feed.",
            McpCategory.PRODUCTIVITY, McpPricingType.FREE_WITH_SIGNUP
        ),
        companion(
            "tomtom",
            "TomTom Places",
            "Search places through the REST API using a private MCP companion.",
            listOf("places: first ten search matches"),
            "https://docs.tomtom.com/search-api/documentation/search-service/fuzzy-search",
            "TOMTOM_API_KEY",
            "Product-specific allowance and metered usage. This adapter is not TomTom's official MCP server.",
            pricing = McpPricingType.FREE_WITH_SIGNUP
        ),
        companion(
            "yelp",
            "Yelp Businesses",
            "Search businesses by term and location with your Yelp plan.",
            listOf("businesses: bounded business search"),
            "https://docs.developer.yelp.com/reference/v3_business_search",
            "YELP_API_KEY",
            "Trial/paid account; no permanent unlimited free allowance is promised.",
            pricing = McpPricingType.PAID
        ),
        companion(
            "eventbrite", "Eventbrite · Organization Events", "Read events for an organization your account can access.",
            listOf("organization_events: authorized organization only"), "https://www.eventbrite.com/platform/new/api",
            "EVENTBRITE_TOKEN", "Not public citywide discovery; the former Event Search endpoint is unavailable.",
            McpCategory.PRODUCTIVITY, McpPricingType.FREE_WITH_SIGNUP
        ),
        companion(
            "arcgis",
            "ArcGIS Geocoding",
            "Find non-stored address candidates through ArcGIS.",
            listOf("geocode: up to five candidates"),
            "https://developers.arcgis.com/rest/geocode/find-address-candidates/",
            "ARCGIS_API_KEY",
            "Non-stored results have retention restrictions. Review terms before using persistent chat history.",
            pricing = McpPricingType.FREE_WITH_SIGNUP
        ),
        companion(
            "openrouteservice",
            "OpenRouteService Walking",
            "Calculate one walking route between supplied coordinates.",
            listOf("walking_route: one route, no fan-out"),
            "https://giscience.github.io/openrouteservice/",
            "ORS_API_KEY",
            "Endpoint-specific usage limits and provider terms apply.",
            pricing = McpPricingType.FREE_WITH_SIGNUP
        ),
        companion(
            "google-places",
            "Google Places",
            "Text Search with a bounded result count and explicit requested fields.",
            listOf("places: text search, no automatic details/photos"),
            "https://developers.google.com/maps/documentation/places/web-service/text-search",
            "GOOGLE_PLACES_API_KEY",
            "Billing, storage and display restrictions apply. Provider-specific chat retention is not implemented; review before activation.",
            pricing = McpPricingType.PAID
        ),
        companion(
            "foursquare",
            "Foursquare Places API",
            "Search places by query and location with a Places service key.",
            listOf("places: first ten matches"),
            "https://docs.foursquare.com/fsq-developers-places/reference/authentication",
            "FOURSQUARE_API_KEY",
            "Metered API, separate from the open-source Places dataset.",
            pricing = McpPricingType.PAID
        ),
        GitHubMarketplacePackage(
            "mapbox",
            McpPreset(
                id = "optional-mapbox", name = "Mapbox Geospatial MCP", description = "Download a setup package for Mapbox's hosted geospatial tools.",
                category = McpCategory.SEARCH, commandOrUrl = "https://mcp.mapbox.com/mcp", alias = "places_mapbox",
                author = "Mapbox · GPT Mobile setup", iconName = "location", suggestedAuthType = "OAUTH",
                pricing = McpPricingType.FREE_WITH_SIGNUP, websiteUrl = "https://github.com/mapbox/mcp-server",
                requiredFields = listOf("Mapbox account and supported OAuth registration"),
                toolCapabilities = listOf("Discover available geospatial tools after sign-in"),
                setupInstructions = "Save, then Authorize in connection settings. OAuth client acceptance on Android requires account testing; download does not establish connectivity."
            ),
            MarketplaceRuntime.HOSTED,
            serviceNotice = "Provider account and usage fees may apply. No map renderer is installed."
        ),
        GitHubMarketplacePackage(
            "geoapify",
            McpPreset(
                id = "optional-geoapify", name = "Geoapify MCP", description = "Download a setup package for hosted geocoding, places and routing tools.",
                category = McpCategory.SEARCH, commandOrUrl = "https://api.geoapify.com/v1/mcp", alias = "places_geoapify",
                author = "Geoapify · GPT Mobile setup", iconName = "location", pricing = McpPricingType.FREE_WITH_SIGNUP,
                websiteUrl = "https://apidocs.geoapify.com/docs/mcp/", requiredEndpointQueryParameter = "apiKey",
                requiredFields = listOf("Geoapify API key in the endpoint apiKey parameter"),
                toolCapabilities = listOf("Discover provider geocoding, places and routing tools"),
                setupInstructions = "Append ?apiKey=<your actual key> to the endpoint. Keep authentication None: the endpoint key is stored through the app's endpoint-secret vault. Usage credits apply."
            ),
            MarketplaceRuntime.HOSTED,
            serviceNotice = "Account required; free allowance then plan-dependent usage."
        ),
        guide("meetup", "Meetup · Setup Plan", "https://www.meetup.com/api/", "Evaluate approved account/API access before implementing event discovery."),
        guide("google-grounding", "Google Maps Grounding · Setup Plan", "https://developers.google.com/maps/ai/grounding-lite", "Separate Google grounding product; not an alias for Google Places."),
        guide("overture", "Overture Places · Data Plan", "https://docs.overturemaps.org/guides/places/", "Plan licensed regional Places extracts; no offline database downloaded yet."),
        guide("foursquare-open", "Foursquare Open Places · Data Plan", "https://opensource.foursquare.com/os-places/", "Evaluate the open dataset separately from the paid Places API."),
        guide("toronto-osm-pack", "Toronto / OSM · Regional Pack Plan", "https://open.toronto.ca/", "Plan versioned, attributed offline washroom and city-service data packs."),
        guide("toronto-library", "Toronto Library Events · Data Plan", "https://www.torontopubliclibrary.ca/opendata/", "Evaluate official branch and community-program feeds; adapter pending.")
    )

    fun find(id: String): GitHubMarketplacePackage? = packages.firstOrNull { it.id == id }
    val allPresets: List<McpPreset> get() = (packages.map { it.preset } + McpPresetCatalog.presets).distinctBy { it.id }
}
