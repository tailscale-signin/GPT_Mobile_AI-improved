package dev.chungjungsoo.gptmobile.data.mcp

/**
 * Data class representing an MCP (Model Context Protocol) Server preset in the marketplace hub.
 */
data class McpPreset(
    val id: String,
    val name: String,
    val description: String,
    val category: McpCategory,
    val transportType: McpTransportType,
    val commandOrUrl: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val iconUrl: String? = null,
    val requiredEnvKeys: List<String> = emptyList()
)

enum class McpCategory {
    SEARCH,
    DEVELOPMENT,
    DATABASE,
    PRODUCTIVITY,
    SYSTEM,
    BROWSER
}

enum class McpTransportType {
    STDIO,
    SSE,
    STREAMABLE_HTTP
}

/**
 * Curated marketplace catalog of popular community MCP presets.
 */
object McpPresetCatalog {
    val presets: List<McpPreset> = listOf(
        McpPreset(
            id = "brave-search",
            name = "Brave Search",
            description = "Web and local search capability using Brave Search API.",
            category = McpCategory.SEARCH,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-brave-search"),
            requiredEnvKeys = listOf("BRAVE_API_KEY")
        ),
        McpPreset(
            id = "github",
            name = "GitHub Integration",
            description = "Search repos, read/write issues, pull requests, and commit files.",
            category = McpCategory.DEVELOPMENT,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-github"),
            requiredEnvKeys = listOf("GITHUB_PERSONAL_ACCESS_TOKEN")
        ),
        McpPreset(
            id = "filesystem",
            name = "Local Filesystem",
            description = "Read, write, and inspect local directory files securely.",
            category = McpCategory.SYSTEM,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/directory")
        ),
        McpPreset(
            id = "postgres",
            name = "PostgreSQL Database",
            description = "Read-only access and schema inspection for PostgreSQL databases.",
            category = McpCategory.DATABASE,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-postgres"),
            requiredEnvKeys = listOf("POSTGRES_CONNECTION_STRING")
        ),
        McpPreset(
            id = "puppeteer",
            name = "Puppeteer Browser",
            description = "Headless browser automation to scrape dynamic pages and take screenshots.",
            category = McpCategory.BROWSER,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-puppeteer")
        ),
        McpPreset(
            id = "fetch",
            name = "Fetch & Scrape",
            description = "Convert HTML web pages into readable Markdown for LLMs.",
            category = McpCategory.SEARCH,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "uvx",
            args = listOf("mcp-server-fetch")
        ),
        McpPreset(
            id = "memory",
            name = "Knowledge Graph Memory",
            description = "Graph-based long-term persistent memory across chat sessions.",
            category = McpCategory.PRODUCTIVITY,
            transportType = McpTransportType.STDIO,
            commandOrUrl = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-memory")
        )
    )
}
