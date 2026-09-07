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
            description = "Privacy-preserving web and local search querying Brave's independent global index without ad tracking or profiling.",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://api.search.brave.com/res/v1",
            transportType = McpTransportType.SSE,
            iconName = "brave",
            author = "Brave Software",
            alias = "brave_search",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Brave Search API Key"),
            toolCapabilities = listOf(
                "brave_web_search: Global web search with rich snippets and summaries",
                "brave_local_search: Nearby businesses, points of interest, and addresses"
            ),
            websiteUrl = "https://brave.com/search/api/"
        ),
        McpPreset(
            id = "github",
            name = "GitHub",
            description = "Official Model Context Protocol integration for GitHub: inspect repos, review PRs, file issues, and search code.",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://api.github.com/mcp",
            transportType = McpTransportType.SSE,
            iconName = "github",
            author = "GitHub / MCP",
            alias = "github",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.FREE_WITH_SIGNUP,
            requiredFields = listOf("Personal Access Token (PAT)"),
            toolCapabilities = listOf(
                "get_file_contents: Read files & directories from any repository",
                "create_or_update_file: Commit changes directly to branches",
                "issue_read/write: Query, label, create, and close issues",
                "pull_request_read: Inspect diffs, checks, reviews, and commits",
                "search_code/repositories: Fast semantic and exact search across GitHub"
            ),
            websiteUrl = "https://github.com/settings/tokens"
        ),
        McpPreset(
            id = "filesystem",
            name = "Local Filesystem",
            description = "Secure filesystem tool with explicit directory sandboxing to read, write, navigate, and search files locally.",
            category = McpCategory.SYSTEM,
            commandOrUrl = "http://localhost:3001/sse",
            transportType = McpTransportType.SSE,
            iconName = "folder",
            author = "Model Context Protocol",
            alias = "filesystem",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "read_file: Retrieve file content as UTF-8 text",
                "write_file: Safely update or write new file content",
                "list_directory: Enumerate directory trees and file metadata",
                "search_files: Fast glob-based file search"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem"
        ),
        McpPreset(
            id = "postgres",
            name = "PostgreSQL",
            description = "Read-only database exploration and schema analysis tool for PostgreSQL instances.",
            category = McpCategory.DATABASE,
            commandOrUrl = "http://localhost:3002/sse",
            transportType = McpTransportType.SSE,
            iconName = "postgres",
            author = "Model Context Protocol",
            alias = "postgres",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "query: Execute read-only SQL queries",
                "list_tables: List schemas, tables, and views",
                "describe_table: Inspect columns, foreign keys, and indices"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/postgres"
        ),
        McpPreset(
            id = "puppeteer",
            name = "Puppeteer Browser",
            description = "Headless browser automation engine to navigate modern SPAs, interact with forms, execute JS, and take screenshots.",
            category = McpCategory.BROWSER,
            commandOrUrl = "http://localhost:3003/sse",
            transportType = McpTransportType.SSE,
            iconName = "puppeteer",
            author = "Model Context Protocol",
            alias = "puppeteer",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "navigate: Load dynamic JavaScript-rendered web pages",
                "screenshot: Capture full-page visual screenshots",
                "click / fill: Interact with forms and web page elements",
                "evaluate: Execute JavaScript in browser context"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/puppeteer"
        ),
        McpPreset(
            id = "fetch",
            name = "Fetch",
            description = "Lightweight web content retriever converting HTML pages directly into LLM-optimized Markdown.",
            category = McpCategory.BROWSER,
            commandOrUrl = "http://localhost:3004/sse",
            transportType = McpTransportType.SSE,
            iconName = "fetch",
            author = "Model Context Protocol",
            alias = "fetch",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "fetch: Download web pages and parse main content into readable Markdown",
                "extract_links: Extract and list outbound hyperlinks from target URL"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/fetch"
        ),
        McpPreset(
            id = "memory",
            name = "Knowledge Graph Memory",
            description = "Persistent long-term cognitive graph memory storing concepts, relations, and user preferences across chat sessions.",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "http://localhost:3005/sse",
            transportType = McpTransportType.SSE,
            iconName = "memory",
            author = "Model Context Protocol",
            alias = "memory",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "create_entities: Persist new facts, concepts, and nodes into graph",
                "create_relations: Link concepts with semantic connections",
                "search_nodes: Traverse and query graph nodes across sessions"
            ),
            websiteUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/memory"
        ),
        McpPreset(
            id = "exa-search",
            name = "Exa Neural Search",
            description = "AI-native semantic web search engine tailored for LLMs with neural embeddings and clean web scraping.",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://api.exa.ai/mcp",
            transportType = McpTransportType.SSE,
            iconName = "exa",
            author = "Exa AI",
            alias = "exa_search",
            suggestedAuthType = "BEARER",
            pricing = McpPricingType.PAID,
            requiredFields = listOf("Exa API Key"),
            toolCapabilities = listOf(
                "search: Neural semantic search returning relevant web content",
                "find_similar: Find links conceptually similar to given URLs",
                "get_contents: Full-text extraction from indexed web content"
            ),
            websiteUrl = "https://exa.ai"
        ),
        McpPreset(
            id = "termux-bridge",
            name = "Termux Android Bridge",
            description = "Direct local Android shell bridge executing Termux commands, device sensor queries, and clipboard sync over loopback.",
            category = McpCategory.SYSTEM,
            commandOrUrl = "http://127.0.0.1:8765/sse",
            transportType = McpTransportType.SSE,
            iconName = "terminal",
            author = "Termux Community",
            alias = "termux_bridge",
            suggestedAuthType = "NONE",
            pricing = McpPricingType.FREE,
            requiredFields = emptyList(),
            toolCapabilities = listOf(
                "exec_command: Run permitted local shell scripts inside Termux",
                "get_device_status: Query battery, storage, and sensors",
                "clipboard_sync: Read and write to Android system clipboard"
            ),
            websiteUrl = "https://termux.dev"
        )
    )

    val categories = listOf("All") + McpCategory.entries.map { it.name }

    fun findById(id: String): McpPreset? = presets.find { it.id == id }

    fun findByAlias(alias: String): McpPreset? = presets.find { it.alias.equals(alias, ignoreCase = true) }

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
                it.alias.lowercase().contains(q) ||
                it.category.name.lowercase().contains(q) ||
                it.pricing.displayName.lowercase().contains(q) ||
                it.toolCapabilities.any { tool -> tool.lowercase().contains(q) }
        }
    }
}
