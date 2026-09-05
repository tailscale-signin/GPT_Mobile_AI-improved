package dev.chungjungsoo.gptmobile.data.catalog

import kotlinx.serialization.Serializable

@Serializable
enum class McpTransportType {
    SSE,
    STDIO,
    WEBSOCKET
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

// Backward-compatibility alias for McpPresetCategory
typealias McpPresetCategory = McpCategory

@Serializable
data class McpPreset(
    val id: String,
    val name: String,
    val description: String,
    val category: McpCategory,
    val commandOrUrl: String,
    val transportType: McpTransportType = McpTransportType.SSE,
    val headers: Map<String, String> = emptyMap(),
    val iconName: String = "extension",
    val author: String = "Community",
    val alias: String = id.replace("-", "_"),
    val suggestedAuthType: String = "NONE",
    val pricing: McpPricingType = McpPricingType.FREE,
    val requiredFields: List<String> = emptyList(),
    val toolCapabilities: List<String> = emptyList(),
    val websiteUrl: String = ""
) {
    // Backward-compatibility aliases
    val url: String get() = commandOrUrl
    val defaultEndpoint: String get() = commandOrUrl
}

typealias McpServerPreset = McpPreset

object McpPresetCatalog {
    val presets = listOf(
        McpPreset(
            id = "brave-search",
            name = "Brave Search",
            description = "Privacy-focused web and local search capabilities querying Brave's global search index without user tracking.",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://api.search.brave.com/res/v1",
            transportType = McpTransportType.SSE,
            iconName = "travel_explore",
            author = "Brave Software",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Bearer API Token"),
            toolCapabilities = listOf(
                "brave_web_search: Search pages and news worldwide",
                "brave_local_search: Query local points of interest and businesses"
            ),
            websiteUrl = "https://brave.com/search/api/"
        ),
        McpPreset(
            id = "github-mcp",
            name = "GitHub Integration",
            description = "Complete repository management: inspect source code, create branches, review pull requests, and manage issues.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://api.github.com/mcp",
            transportType = McpTransportType.SSE,
            iconName = "terminal",
            author = "Model Context Protocol",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Personal Access Token (PAT)"),
            toolCapabilities = listOf(
                "get_file_contents: Read files from any accessible repo",
                "create_or_update_file: Commit changes directly to branches",
                "issue_read/issue_write: Inspect and file issues",
                "pull_request_read: Review PR diffs and CI statuses"
            ),
            websiteUrl = "https://github.com/settings/tokens"
        ),
        McpPreset(
            id = "filesystem-mcp",
            name = "Local Filesystem",
            description = "Local file operations inside sandbox boundaries. Read, write, move, search, and inspect directory trees.",
            category = McpCategory.SYSTEM,
            commandOrUrl = "http://localhost:3001/sse",
            transportType = McpTransportType.SSE,
            iconName = "folder",
            author = "Model Context Protocol",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "read_file: Retrieve file content as UTF-8 text",
                "write_file: Write or update file content safely",
                "list_directory: Enumerate directory trees and file metadata",
                "search_files: Fast glob-based file lookup"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers"
        ),
        McpPreset(
            id = "postgres-mcp",
            name = "PostgreSQL Inspector",
            description = "Safe SQL inspector for PostgreSQL: analyze relational schemas, validate queries, and inspect table rows.",
            category = McpCategory.DATABASE,
            commandOrUrl = "http://localhost:3002/sse",
            transportType = McpTransportType.SSE,
            iconName = "dns",
            author = "Model Context Protocol",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "query: Execute read-only SQL queries",
                "list_tables: List schemas, tables, and views",
                "describe_table: Inspect columns, primary keys, and indices"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers"
        ),
        McpPreset(
            id = "puppeteer-mcp",
            name = "Puppeteer Browser",
            description = "Headless browser automation engine: navigate web applications, click elements, evaluate JavaScript, and capture screenshots.",
            category = McpCategory.BROWSER,
            commandOrUrl = "http://localhost:3003/sse",
            transportType = McpTransportType.SSE,
            iconName = "public",
            author = "Model Context Protocol",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "navigate: Load dynamic JavaScript-rendered web pages",
                "screenshot: Capture full-page visual screenshots",
                "click / fill: Interact with buttons and input fields",
                "evaluate: Execute custom JavaScript snippets"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers"
        ),
        McpPreset(
            id = "fetch-mcp",
            name = "Fetch & Markdown Scraper",
            description = "High-speed URL retriever and web content extractor converting HTML pages into structured clean Markdown.",
            category = McpCategory.SEARCH,
            commandOrUrl = "http://localhost:3004/sse",
            transportType = McpTransportType.SSE,
            iconName = "download",
            author = "Model Context Protocol",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "fetch: Download web pages and parse main content into readable Markdown",
                "extract_links: Collect outbound hyperlinks from target URL"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers"
        ),
        McpPreset(
            id = "memory-mcp",
            name = "Knowledge Graph Memory",
            description = "Persistent long-term cognitive memory graph storing entities, relations, and user preferences across multiple chats.",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "http://localhost:3005/sse",
            transportType = McpTransportType.SSE,
            iconName = "psychology",
            author = "Model Context Protocol",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "create_entities: Persist new facts and nodes into memory graph",
                "create_relations: Link concepts with semantic relationships",
                "search_nodes: Semantic graph traversal across conversations"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers"
        ),
        McpPreset(
            id = "exa-search-mcp",
            name = "Exa Neural Search",
            description = "AI-native semantic web search engine designed for LLMs, capable of deep similarity search and live web crawl.",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://api.exa.ai/mcp",
            transportType = McpTransportType.SSE,
            iconName = "travel_explore",
            author = "Exa AI",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.PAID,
            requiredFields = listOf("Exa API Key"),
            toolCapabilities = listOf(
                "search: Neural semantic search returning relevant web pages",
                "find_similar: Find links conceptually similar to given URLs",
                "get_contents: Full-text extraction from indexed web content"
            ),
            websiteUrl = "https://exa.ai"
        ),
        McpPreset(
            id = "termux-native-mcp",
            name = "Termux Android Bridge",
            description = "Execute local device tools, termux CLI scripts, and local Android integrations directly over local loopback.",
            category = McpCategory.SYSTEM,
            commandOrUrl = "http://127.0.0.1:8765/sse",
            transportType = McpTransportType.SSE,
            iconName = "terminal",
            author = "Termux Community",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "exec_command: Run permitted local shell scripts inside Termux",
                "get_device_status: Query battery, storage, and sensors",
                "clipboard_sync: Exchange text with Android system clipboard"
            ),
            websiteUrl = "https://termux.dev"
        )
    )

    val categories = listOf("All") + McpCategory.entries.map { it.name }

    fun findById(id: String): McpPreset? = presets.find { it.id == id }

    fun getByCategory(category: McpCategory): List<McpPreset> {
        return presets.filter { it.category == category }
    }

    fun filterByCategory(category: McpCategory): List<McpPreset> {
        return presets.filter { it.category == category }
    }

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
                it.category.name.lowercase().contains(q) ||
                it.pricing.displayName.lowercase().contains(q)
        }
    }
}
