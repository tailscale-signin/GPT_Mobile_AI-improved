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
            verifiedRemote = true
        ),
        McpPreset(
            id = "context7", name = "Context7 Documentation", description = "Current library documentation and code examples. Public access has rate limits; choose Bearer to add a Context7 API key.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.context7.com/mcp", author = "Upstash",
            alias = "context7", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Resolve library identifiers", "Search current library documentation"),
            websiteUrl = "https://github.com/upstash/context7", verifiedRemote = true
        ),
        McpPreset(
            id = "tavily-mcp", name = "Tavily Search & Crawl", description = "Search, extract, crawl and map websites through Tavily. Requires a Tavily API key; usage is subject to your plan.",
            category = McpCategory.SEARCH, commandOrUrl = "https://mcp.tavily.com/mcp/", author = "Tavily",
            alias = "tavily_mcp", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Search web", "Extract and crawl webpages", "Map website URLs"),
            websiteUrl = "https://github.com/tavily-ai/tavily-mcp", verifiedRemote = true
        ),
        McpPreset(
            id = "firecrawl-mcp", name = "Firecrawl Web Research", description = "Hosted scrape, search and parse tools with a limited keyless tier. Choose Bearer for your Firecrawl API key and account limits.",
            category = McpCategory.SEARCH, commandOrUrl = "https://mcp.firecrawl.dev/v2/mcp", author = "Firecrawl",
            alias = "firecrawl_mcp", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Search web", "Scrape webpages", "Parse documents"),
            websiteUrl = "https://github.com/firecrawl/firecrawl-mcp-server", verifiedRemote = true
        ),
        McpPreset(
            id = "jina-mcp", name = "Jina Reader & Search", description = "Read webpages and retrieve content. Some tools, including search, require a Jina API key; choose Bearer to enable them.",
            category = McpCategory.SEARCH, commandOrUrl = "https://mcp.jina.ai/v1", author = "Jina AI",
            alias = "jina_mcp", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Read URLs", "Search with a Jina key", "Rerank results"),
            websiteUrl = "https://github.com/jina-ai/MCP", verifiedRemote = true
        ),
        McpPreset(
            id = "huggingface", name = "Hugging Face Hub", description = "Search models, datasets, papers and Spaces. Use a Hugging Face token and configure tools in your account.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://huggingface.co/mcp", author = "Hugging Face",
            alias = "huggingface", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Search models and datasets", "Find papers and Spaces", "Read model cards"),
            websiteUrl = "https://github.com/huggingface/hf-mcp-server", verifiedRemote = true
        ),
        McpPreset(
            id = "microsoft-learn", name = "Microsoft Learn", description = "Search official Microsoft documentation and code samples without an account.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://learn.microsoft.com/api/mcp", author = "Microsoft",
            alias = "microsoft_learn", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Search documentation", "Fetch documentation", "Search code samples"),
            websiteUrl = "https://github.com/MicrosoftDocs/mcp", verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare-docs", name = "Cloudflare Documentation", description = "Search current Cloudflare product documentation using its public remote server.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://docs.mcp.cloudflare.com/mcp", author = "Cloudflare",
            alias = "cloudflare_docs", suggestedAuthType = "NONE", pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Search Cloudflare documentation"),
            websiteUrl = "https://github.com/cloudflare/mcp-server-cloudflare", verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare-radar", name = "Cloudflare Radar", description = "Explore internet traffic, outages and security insights. Requires a Cloudflare token with Radar permissions.",
            category = McpCategory.SEARCH, commandOrUrl = "https://radar.mcp.cloudflare.com/mcp", author = "Cloudflare",
            alias = "cloudflare_radar", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Internet traffic insights", "Outage and security trends"),
            websiteUrl = "https://github.com/cloudflare/mcp-server-cloudflare", verifiedRemote = true
        ),
        McpPreset(
            id = "neon", name = "Neon Postgres", description = "Inspect Neon projects and query Postgres in read-only mode. Requires a Neon API key. Scope projects with the endpoint query options.",
            category = McpCategory.DATABASE, commandOrUrl = "https://mcp.neon.tech/mcp?readonly=true", author = "Neon",
            alias = "neon", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect projects and schemas", "Read-only SQL queries"),
            websiteUrl = "https://github.com/neondatabase/mcp-server-neon", verifiedRemote = true
        ),
        McpPreset(
            id = "supabase", name = "Supabase Database", description = "Inspect Supabase projects, schema and data. Read-only SQL is selected by default; add project_ref to scope one project. Requires a personal access token.",
            category = McpCategory.DATABASE, commandOrUrl = "https://mcp.supabase.com/mcp?read_only=true", author = "Supabase",
            alias = "supabase", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect projects and schema", "Read-only database queries", "Search documentation"),
            websiteUrl = "https://github.com/supabase/mcp", verifiedRemote = true
        ),
        McpPreset(
            id = "stripe", name = "Stripe Account Tools", description = "Inspect and manage Stripe resources allowed by your Agent API key. Select only the tools your AI profile needs.",
            category = McpCategory.PRODUCTIVITY, commandOrUrl = "https://mcp.stripe.com", author = "Stripe",
            alias = "stripe", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Customer and payment resources", "Billing and product information", "Search Stripe documentation"),
            websiteUrl = "https://github.com/stripe/ai", verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare", name = "Cloudflare API", description = "Search and call Cloudflare APIs with the permissions of your Cloudflare API token.",
            category = McpCategory.DEVELOPMENT, commandOrUrl = "https://mcp.cloudflare.com/mcp", author = "Cloudflare",
            alias = "cloudflare", suggestedAuthType = "BEARER", pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Search Cloudflare APIs", "Execute authorized API operations"),
            websiteUrl = "https://github.com/cloudflare/mcp", verifiedRemote = true
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
