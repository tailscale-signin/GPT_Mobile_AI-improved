package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import java.net.URI
import java.util.Locale

data class ToolServiceDefinition(
    val id: String,
    val name: String,
    val description: String,
    val iconName: String,
    val usesNetwork: Boolean = false,
    val supportsLocalTools: Boolean = !usesNetwork
)

/** Shared identities for the service list, profile controls and runtime tool filtering. */
object ToolServiceCatalog {
    /** A connection-specific action stays exact; service actions include every matching provider. */
    fun connectionsForPlugin(pluginId: String, connections: List<ToolConnection>): List<ToolConnection> =
        connections.filter { connection ->
            if (pluginId.startsWith("connection:")) {
                ToolPluginId.connection(connection.connectionUid) == pluginId
            } else {
                forConnection(connection).id == pluginId ||
                    (
                        pluginId == ToolPluginId.WEB_SEARCH &&
                            (
                                connection.type in setOf(ToolConnectionType.BRAVE, ToolConnectionType.EXA, ToolConnectionType.FIRECRAWL, ToolConnectionType.PERPLEXITY) ||
                                    dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog.findByAlias(connection.alias)?.webSearchToolNames?.isNotEmpty() == true
                                )
                        )
            }
        }

    val integrated = listOf(
        ToolServiceDefinition(ToolPluginId.MODEL_DELEGATION, "Model Delegation", "Let a configured helper research and process information.", "delegation"),
        ToolServiceDefinition(ToolPluginId.LOCAL_MEMORY, "Local Memory", "Recall and capture private memory on your device.", "memory"),
        ToolServiceDefinition(ToolPluginId.CURRENT_DATE, "Date & Time", "Current date and time, calculated on your device.", "schedule"),
        ToolServiceDefinition(ToolPluginId.CALCULATOR, "Calculator", "Evaluate arithmetic expressions on your device.", "calculator"),
        ToolServiceDefinition(ToolPluginId.READ_FILES, "Files", "Read files shared with the app.", "folder"),
        ToolServiceDefinition(ToolPluginId.READ_URL, "Web Pages", "Read web pages using the app's built-in plugin.", "web", true),
        ToolServiceDefinition(ToolPluginId.GITHUB, "GitHub", "Repository tools and workspace access in one service.", "github", true),
        ToolServiceDefinition(ToolPluginId.AMAZON_SEARCH, "Amazon Search", "Product search, deals and price tracking. Connect a search key or an Amazon MCP server.", "amazon", true),
        ToolServiceDefinition(ToolPluginId.AMAZON_FREE, "Amazon Research Free", "Preview: Amazon Canada/US search and details, local history and manual targets. No API key; public-page availability varies.", "shopping", true, supportsLocalTools = true),
        ToolServiceDefinition(ToolPluginId.NEWS, "News", "Google News, Google Trends and Hacker News, with optional unified MCP providers.", "news", true),
        ToolServiceDefinition(ToolPluginId.AIRBNB, "Airbnb", "Public listing search and details directly in the app without sign-up. Optional OpenBnB connections remain available.", "airbnb", true),
        ToolServiceDefinition(ToolPluginId.GOOGLE_PLACES, "Google Places", "Text and nearby place search, place details, and Google Maps Grounding MCP. A Google API key is required.", "google", true),
        ToolServiceDefinition(ToolPluginId.WEB_SEARCH, "Web Search", "Search the web with the app's built-in engines.", "search", true),
        ToolServiceDefinition(ToolPluginId.DEVICE_LOCATION, "Device Location", "Share the phone's location when you allow it.", "location")
    )

    fun forPackage(entry: GitHubMarketplacePackage): ToolServiceDefinition {
        val provider = when (entry.provider) {
            "nominatim", "overpass", "openstreetmap" -> "openstreetmap"
            "google-places", "google-grounding" -> "google"
            "foursquare-open" -> "foursquare"
            "toronto-library", "toronto-osm-pack" -> "toronto"
            else -> entry.provider
        }
        if (provider == "google") return integrated.first { it.id == ToolPluginId.GOOGLE_PLACES }
        return ToolServiceDefinition("service:$provider", providerName(provider, entry.preset.name), entry.preset.description, provider, true)
    }

    fun forConnection(connection: ToolConnection): ToolServiceDefinition {
        val provider = when (connection.type) {
            ToolConnectionType.GITHUB -> "github"
            ToolConnectionType.AMAZON_SERPAPI -> "amazon"
            ToolConnectionType.BRAVE -> "brave"
            ToolConnectionType.EXA -> "exa"
            ToolConnectionType.FIRECRAWL -> "firecrawl"
            ToolConnectionType.PERPLEXITY -> "perplexity"
            else -> remoteProvider(connection)
        }
        if (provider == "github") return integrated.first { it.id == ToolPluginId.GITHUB }
        if (provider == "amazon") return integrated.first { it.id == ToolPluginId.AMAZON_SEARCH }
        if (provider == "airbnb") return integrated.first { it.id == ToolPluginId.AIRBNB }
        if (provider == "news") return integrated.first { it.id == ToolPluginId.NEWS }
        if (provider == "google") return integrated.first { it.id == ToolPluginId.GOOGLE_PLACES }
        return ToolServiceDefinition(
            id = provider?.let { "service:$it" } ?: ToolPluginId.connection(connection.connectionUid),
            name = provider?.let { providerName(it, connection.name) } ?: connection.name,
            description = if (connection.type == ToolConnectionType.MCP) "Tools run on your connected MCP server." else "A plugin running in the app, with provider access.",
            iconName = provider ?: "mcp",
            usesNetwork = true
        )
    }

    private fun remoteProvider(connection: ToolConnection): String? {
        val host = runCatching { URI(connection.endpointUrl.orEmpty()).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host == "api.githubcopilot.com" || host == "gitmcp.io" || host.endsWith(".gitmcp.io")) return "github"
        if (host == "mapstools.googleapis.com") return "google"
        if (connection.alias == "gitmcp_docs") return "github"
        if (connection.alias == "free_search_mcp") return "free_search"
        val names = "${connection.name} ${connection.alias}".lowercase(Locale.ROOT)
        if (host == "mcp.openbnb.ai" || Regex("\\b(airbnb|openbnb)\\b").containsMatchIn(names)) return "airbnb"
        if (Regex("\\b(news|hacker_news|google_news|news_bundle)\\b").containsMatchIn(names)) return "news"
        if (names.contains("jannafta") || names.contains("jan nafta")) return "amazon"
        val providers = listOf("free_search", "github", "amazon", "searxng", "youtube", "openstreetmap", "brave", "exa", "firecrawl", "perplexity", "google", "slack", "linear", "sentry", "atlassian", "cloudflare", "stripe", "supabase", "tavily", "context7", "deepwiki", "huggingface", "notion", "mem0", "supermemory", "tomtom", "foursquare", "ticketmaster", "geoapify", "yelp", "mapbox", "airtable", "asana", "vercel", "netlify", "neon", "prisma", "semgrep", "jina", "todoist", "microsoft", "excalidraw", "agentset", "dbhub", "chat2db")
        if (listOf("openstreetmap", "nominatim", "overpass").any { Regex("\\b$it\\b").containsMatchIn(names) || host.contains(it) }) return "openstreetmap"
        return providers.firstOrNull { provider ->
            host == "$provider.com" ||
                host.endsWith(".$provider.com") ||
                host == "$provider.ai" ||
                host.endsWith(".$provider.ai") ||
                host.split('.').dropLast(1).lastOrNull() == provider ||
                Regex("\\b$provider\\b").containsMatchIn(names)
        }
    }

    private fun providerName(provider: String, fallback: String): String = when (provider) {
        "openstreetmap" -> "OpenStreetMap"
        "google" -> "Google"
        "toronto" -> "Toronto Open Data"
        "foursquare" -> "Foursquare"
        "github" -> "GitHub"
        "free_search" -> "Free Search"
        "amazon" -> "Amazon Search"
        "refuge" -> "REFUGE Restrooms"
        "openrouteservice" -> "OpenRouteService"
        "arcgis" -> "ArcGIS"
        "brave" -> "Brave Search"
        "firecrawl" -> "Firecrawl"
        "perplexity" -> "Perplexity"
        "exa" -> "Exa"
        "mapbox" -> "Mapbox"
        "tomtom" -> "TomTom"
        "geoapify" -> "Geoapify"
        "eventbrite" -> "Eventbrite"
        "ticketmaster" -> "Ticketmaster"
        "huggingface" -> "Hugging Face"
        "context7" -> "Context7"
        "deepwiki" -> "DeepWiki"
        "searxng" -> "SearXNG"
        "youtube" -> "YouTube Transcripts"
        "mem0" -> "Mem0"
        "jina" -> "Jina AI"
        "dbhub" -> "DBHub"
        "chat2db" -> "Chat2DB"
        else -> fallback.substringBefore(" · ").substringBefore(" MCP")
    }
}
