package dev.chungjungsoo.gptmobile.data.catalog

import kotlinx.serialization.Serializable

/**
 * Transport types visible in the marketplace.
 *
 * GPT Mobile can directly connect to remote MCP servers using Streamable HTTP.
 * STDIO is retained only to describe app-integrated tools; Android does not pretend
 * that a remote STDIO package is installable when no local process host exists.
 */
@Serializable
enum class McpTransportType {
    STREAMABLE_HTTP,
    STDIO
}

@Serializable
enum class McpCategory(val displayName: String) {
    SEARCH("Search"),
    DEVELOPMENT("Development"),
    SYSTEM("System"),
    DATABASE("Database"),
    BROWSER("Browser"),
    PRODUCTIVITY("Productivity")
}

@Serializable
enum class McpPricingType(val displayName: String) {
    FREE("Free"),
    FREE_WITH_SIGNUP("Free with sign up"),
    PAID("Paid")
}

typealias McpPresetCategory = McpCategory

@Serializable
data class McpPreset(
    val id: String,
    val name: String,
    val description: String,
    val category: McpCategory,
    val commandOrUrl: String,
    val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    val iconName: String = "extension",
    val author: String = "Community",
    val alias: String = id.replace("-", "_"),
    val suggestedAuthType: String = "NONE",
    val pricing: McpPricingType = McpPricingType.FREE,
    val requiredFields: List<String> = emptyList(),
    val toolCapabilities: List<String> = emptyList(),
    val websiteUrl: String = "",
    val isPreinstalled: Boolean = false,
    val verifiedRemote: Boolean = false
) {
    val url: String get() = commandOrUrl
    val defaultEndpoint: String get() = commandOrUrl
    val isDirectlyInstallable: Boolean
        get() = !isPreinstalled && transportType == McpTransportType.STREAMABLE_HTTP
}

typealias McpServerPreset = McpPreset

/**
 * Curated marketplace catalog.
 *
 * Only presets that GPT Mobile can actually use are surfaced:
 * - app-integrated built-ins, or
 * - remote Streamable HTTP endpoints.
 *
 * We intentionally do not advertise arbitrary localhost /sse or STDIO package examples
 * as one-tap installs because the Android client does not launch Node/Python MCP servers.
 */
object McpPresetCatalog {
    val presets = listOf(
        McpPreset(
            id = "builtin-web",
            name = "Web Search & URL Reader",
            description = "Integrated search and webpage reading tools available to AI profiles without installing a separate MCP server.",
            category = McpCategory.SEARCH,
            commandOrUrl = "builtin://web",
            transportType = McpTransportType.STDIO,
            iconName = "online_search",
            author = "GPT Mobile AI",
            alias = "builtin_web",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf(
                "web_search: Search the web through the configured search backend",
                "read_url: Retrieve and normalize supported web content"
            ),
            isPreinstalled = true
        ),
        McpPreset(
            id = "device-location",
            name = "Device Location",
            description = "Native Android location tool. Profiles that enable it can request the phone's current coordinates after Android location permission is granted.",
            category = McpCategory.SYSTEM,
            commandOrUrl = "builtin://device_location",
            transportType = McpTransportType.STDIO,
            iconName = "location",
            author = "GPT Mobile AI",
            alias = "device_location",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf(
                "device_location: Current latitude, longitude, accuracy, altitude and provider metadata"
            ),
            isPreinstalled = true
        ),
        McpPreset(
            id = "github-all",
            name = "GitHub MCP — All Toolsets",
            description = "GitHub's hosted remote MCP server with all available toolsets. Supports repository, issue, pull request, Actions and other GitHub operations allowed by your token.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://api.githubcopilot.com/mcp/x/all",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "github",
            author = "GitHub",
            alias = "github",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("GitHub Personal Access Token"),
            toolCapabilities = listOf(
                "Repository and code operations",
                "Issues, pull requests and reviews",
                "GitHub Actions and workflow operations",
                "Additional GitHub MCP toolsets exposed by the hosted service"
            ),
            websiteUrl = "https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md",
            verifiedRemote = true
        ),
        McpPreset(
            id = "github-readonly",
            name = "GitHub MCP — Read Only",
            description = "GitHub's hosted remote MCP server with all toolsets constrained to read-only operations.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://api.githubcopilot.com/mcp/x/all/readonly",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "github",
            author = "GitHub",
            alias = "github_readonly",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("GitHub Personal Access Token"),
            toolCapabilities = listOf(
                "Repository inspection and code search",
                "Read-only issues and pull request access",
                "Read-only workflow and Actions visibility"
            ),
            websiteUrl = "https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md",
            verifiedRemote = true
        ),
        McpPreset(
            id = "exa-mcp",
            name = "Exa MCP Search",
            description = "Exa's hosted remote MCP endpoint for web search, code search, research and webpage retrieval. The hosted MCP service can be used without a separate local server.",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://mcp.exa.ai/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "exa",
            author = "Exa",
            alias = "exa_mcp",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf(
                "Web and news search",
                "Code search",
                "Research workflows",
                "Webpage content retrieval"
            ),
            websiteUrl = "https://exa.ai/mcp",
            verifiedRemote = true,
        McpPreset(
            id = "context7", name = "Context7 Documentation", description = "Up-to-date library and framework documentation from the official Context7 remote MCP service.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.context7.com/mcp", author = "Upstash",
            alias = "context7", suggestedAuthType = "API_KEY", pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Context7 API key (optional for higher limits)"),
            toolCapabilities = listOf("Resolve library identifiers", "Search current library documentation"),
            websiteUrl = "https://github.com/upstash/context7", verifiedRemote = true
        ),
        McpPreset(
            id = "sentry", name = "Sentry", description = "Official hosted Sentry MCP for issues, events, traces, releases and debugging workflows.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.sentry.dev/mcp", author = "Sentry",
            alias = "sentry", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect issues and events", "Query traces and projects", "Debug releases and errors"),
            websiteUrl = "https://github.com/getsentry/sentry-mcp", verifiedRemote = true
        ),
        McpPreset(
            id = "notion", name = "Notion", description = "Official hosted Notion MCP for semantic workspace search plus page reading and editing.",
            category = McpCategory.PRODUCTIVITY, commandOrUrl = "https://mcp.notion.com/mcp", author = "Notion",
            alias = "notion", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Semantic workspace search", "Read pages as Markdown", "Create and edit workspace content"),
            websiteUrl = "https://github.com/makenotion/notion-mcp-server", verifiedRemote = true
        ),
        McpPreset(
            id = "linear", name = "Linear", description = "Official hosted Linear MCP for issues, projects, initiatives, comments and team workflows.",
            category = McpCategory.PRODUCTIVITY, commandOrUrl = "https://mcp.linear.app/mcp", author = "Linear",
            alias = "linear", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Find and update issues", "Work with projects and initiatives", "Create comments and project updates"),
            websiteUrl = "https://github.com/linear/linear", verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare", name = "Cloudflare", description = "Official Cloudflare remote MCP for Workers, DNS, storage, Zero Trust and account resources.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.cloudflare.com/mcp", author = "Cloudflare",
            alias = "cloudflare", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect Cloudflare resources", "Manage Workers and storage", "Query DNS and Zero Trust configuration"),
            websiteUrl = "https://github.com/cloudflare/mcp-server-cloudflare", verifiedRemote = true
        ),
        McpPreset(
            id = "vercel", name = "Vercel", description = "Official Vercel remote MCP for projects, deployments, logs and platform documentation.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.vercel.com", author = "Vercel",
            alias = "vercel", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect projects and deployments", "Analyze deployment logs", "Search Vercel documentation"),
            websiteUrl = "https://github.com/vercel", verifiedRemote = true
        ),
        McpPreset(
            id = "datadog", name = "Datadog", description = "Official Datadog MCP for APM, logs, metrics, monitors, dashboards and security signals.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.datadoghq.com/v1/mcp", author = "Datadog",
            alias = "datadog", suggestedAuthType = "OAUTH", pricing = McpPricingType.PAID,
            toolCapabilities = listOf("Query logs and metrics", "Inspect APM and monitors", "Analyze dashboards and security signals"),
            websiteUrl = "https://github.com/DataDog", verifiedRemote = true
        ),
        McpPreset(
            id = "grafana-cloud", name = "Grafana Cloud", description = "Official Grafana Cloud MCP for metrics, logs, dashboards, alerts and incident investigations.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.grafana.com/mcp", author = "Grafana Labs",
            alias = "grafana_cloud", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Query metrics and logs", "Inspect dashboards and alerts", "Investigate incidents"),
            websiteUrl = "https://github.com/grafana", verifiedRemote = true
        ),
        McpPreset(
            id = "new-relic", name = "New Relic", description = "Official New Relic remote MCP for observability discovery, NRQL, alerts and performance analysis.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.newrelic.com/mcp/", author = "New Relic",
            alias = "new_relic", suggestedAuthType = "OAUTH", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Run observability discovery", "Query NRQL data", "Analyze alerts and performance"),
            websiteUrl = "https://github.com/newrelic", verifiedRemote = true
        ),
        McpPreset(
            id = "grep-vercel", name = "Grep by Vercel", description = "Hosted MCP for searching real-world code examples across public GitHub repositories.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.grep.app", author = "Vercel",
            alias = "grep_vercel", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Search public GitHub code", "Find implementation examples across repositories"),
            websiteUrl = "https://github.com/vercel", verifiedRemote = true
        )
        )
    )

    val categories = listOf("All") + McpCategory.entries.map { it.name }

    fun findById(id: String): McpPreset? = presets.find { it.id == id }

    fun findByAlias(alias: String): McpPreset? = presets.find { it.alias.equals(alias, ignoreCase = true) }

    fun getByCategory(category: McpCategory): List<McpPreset> = presets.filter { it.category == category }

    fun filterByCategory(category: McpCategory): List<McpPreset> = presets.filter { it.category == category }

    fun filterByCategory(categoryName: String): List<McpPreset> {
        if (categoryName.equals("All", ignoreCase = true)) return presets
        return presets.filter { it.category.name.equals(categoryName, ignoreCase = true) }
    }

    fun searchPresets(query: String): List<McpPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return presets
        return presets.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.alias.lowercase().contains(q) ||
                it.category.name.lowercase().contains(q) ||
                it.pricing.displayName.lowercase().contains(q) ||
                it.toolCapabilities.any { tool -> tool.lowercase().contains(q) }
        }
    }
}
