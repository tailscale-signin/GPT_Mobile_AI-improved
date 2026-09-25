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
            id = "microsoft-learn",
            name = "Microsoft Learn",
            description = "Official Microsoft Learn documentation and code-sample search through Microsoft's public hosted MCP server.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://learn.microsoft.com/api/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "microsoft",
            author = "Microsoft",
            alias = "microsoft_learn",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Microsoft documentation search", "Documentation fetch", "Official code sample search"),
            websiteUrl = "https://github.com/microsoftdocs/mcp",
            verifiedRemote = true
        ),
        McpPreset(
            id = "context7",
            name = "Context7",
            description = "Up-to-date library and framework documentation from Context7's hosted Streamable HTTP MCP server.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://mcp.context7.com/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "book",
            author = "Upstash",
            alias = "context7",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Resolve library identifiers", "Retrieve current library documentation"),
            websiteUrl = "https://github.com/upstash/context7",
            verifiedRemote = true
        ),
        McpPreset(
            id = "deepwiki",
            name = "DeepWiki",
            description = "No-auth hosted MCP access to Devin-generated documentation for public GitHub repositories.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://mcp.deepwiki.com/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "book",
            author = "Cognition",
            alias = "deepwiki",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Ask repository questions", "Read wiki structure", "Read generated repository documentation"),
            websiteUrl = "https://github.com/CognitionAI/deepwiki",
            verifiedRemote = true
        ),
        McpPreset(
            id = "supabase",
            name = "Supabase",
            description = "Official hosted Supabase MCP server for database, docs, debugging, development, functions, storage and branching workflows.",
            category = McpCategory.DATABASE,
            commandOrUrl = "https://mcp.supabase.com/mcp?read_only=true",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "database",
            author = "Supabase",
            alias = "supabase",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Database inspection", "Docs search", "Project debugging", "Development tools"),
            websiteUrl = "https://github.com/supabase-community/supabase-mcp",
            verifiedRemote = true
        ),
        McpPreset(
            id = "neon",
            name = "Neon",
            description = "Official hosted Neon database MCP with OAuth and a safe read-only URL preset.",
            category = McpCategory.DATABASE,
            commandOrUrl = "https://mcp.neon.tech/mcp?readonly=true",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "database",
            author = "Neon",
            alias = "neon",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("List projects", "Inspect schemas", "Run read-only SQL", "View database performance"),
            websiteUrl = "https://github.com/neondatabase/mcp-server-neon",
            verifiedRemote = true
        ),
        McpPreset(
            id = "linear",
            name = "Linear",
            description = "Official hosted Linear MCP server for issues, projects and workspace workflows.",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "https://mcp.linear.app/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "linear",
            author = "Linear",
            alias = "linear",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Search issues", "Inspect projects", "Manage Linear work items"),
            websiteUrl = "https://github.com/linear/cursor-plugin",
            verifiedRemote = true
        ),
        McpPreset(
            id = "huggingface",
            name = "Hugging Face",
            description = "Official Hugging Face hosted Streamable HTTP MCP for Hub search and configured Spaces tools.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://huggingface.co/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "huggingface",
            author = "Hugging Face",
            alias = "huggingface",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Hugging Face access token"),
            toolCapabilities = listOf("Search the Hugging Face Hub", "Access configured Gradio Spaces", "Hub API tools"),
            websiteUrl = "https://github.com/huggingface/hf-mcp-server",
            verifiedRemote = true
        ),
        McpPreset(
            id = "stripe",
            name = "Stripe",
            description = "Stripe's official hosted MCP server for permitted Stripe API operations.",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "https://mcp.stripe.com",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "payments",
            author = "Stripe",
            alias = "stripe",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect Stripe resources", "Use Stripe API tools within account permissions"),
            websiteUrl = "https://github.com/stripe/ai",
            verifiedRemote = true
        ),
        McpPreset(
            id = "atlassian",
            name = "Atlassian",
            description = "Official Atlassian Rovo MCP server for Jira, Confluence, JSM, Bitbucket, Compass, Loom and Teamwork Graph data.",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "https://mcp.atlassian.com/v2/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "atlassian",
            author = "Atlassian",
            alias = "atlassian",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Jira work items", "Confluence content", "Bitbucket and Compass", "Atlassian workspace search"),
            websiteUrl = "https://github.com/atlassian/atlassian-mcp-server",
            verifiedRemote = true
        ),
        McpPreset(
            id = "sentry",
            name = "Sentry",
            description = "Sentry's hosted MCP server for issue, error and observability workflows.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://mcp.sentry.dev/mcp/sentry?experimental=1",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "bug",
            author = "Sentry",
            alias = "sentry",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Inspect Sentry issues", "Query error context", "Observability workflows"),
            websiteUrl = "https://github.com/getsentry/sentry-mcp",
            verifiedRemote = true
        ),
        McpPreset(
            id = "vercel",
            name = "Vercel",
            description = "Official Vercel remote MCP for projects, deployments, logs and documentation.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://mcp.vercel.com",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "cloud",
            author = "Vercel",
            alias = "vercel",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Search Vercel docs", "List projects and deployments", "Inspect deployment logs"),
            websiteUrl = "https://github.com/vercel/vercel-mcp-overview",
            verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare-docs",
            name = "Cloudflare Documentation",
            description = "Public Cloudflare documentation server over Streamable HTTP.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://docs.mcp.cloudflare.com/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "cloud",
            author = "Cloudflare",
            alias = "cloudflare_docs",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            toolCapabilities = listOf("Search Cloudflare documentation", "Retrieve current platform reference"),
            websiteUrl = "https://github.com/cloudflare/mcp-server-cloudflare",
            verifiedRemote = true
        ),
        McpPreset(
            id = "cloudflare-browser",
            name = "Cloudflare Browser Rendering",
            description = "Official Cloudflare hosted MCP endpoint for browser rendering workflows.",
            category = McpCategory.BROWSER,
            commandOrUrl = "https://browser.mcp.cloudflare.com/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP,
            iconName = "browser",
            author = "Cloudflare",
            alias = "cloudflare_browser",
            suggestedAuthType = "OAUTH",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            toolCapabilities = listOf("Browser rendering", "Web interaction through Cloudflare infrastructure"),
            websiteUrl = "https://github.com/cloudflare/mcp-server-cloudflare",
            verifiedRemote = true
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
