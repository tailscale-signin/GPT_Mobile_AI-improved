package dev.chungjungsoo.gptmobile.data.catalog

import kotlinx.serialization.Serializable

@Serializable
enum class McpTransportType {
    SSE,
    STDIO,
    WEBSOCKET
}

@Serializable
enum class McpCategory {
    SEARCH,
    DEVELOPMENT,
    SYSTEM,
    DATABASE,
    BROWSER,
    PRODUCTIVITY
}

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
    val author: String = "Community"
) {
    // Backward-compatibility aliases for McpServerPreset consumers
    val url: String get() = commandOrUrl
}

typealias McpServerPreset = McpPreset

object McpPresetCatalog {
    val presets = listOf(
        McpPreset(
            id = "brave-search",
            name = "Brave Search",
            description = "Web and local search capabilities via Brave Search API",
            category = McpCategory.SEARCH,
            commandOrUrl = "https://api.search.brave.com/res/v1",
            transportType = McpTransportType.SSE,
            iconName = "travel_explore",
            author = "Brave Software"
        ),
        McpPreset(
            id = "github-mcp",
            name = "GitHub Integration",
            description = "Interact with GitHub repositories, issues, PRs, and commits",
            category = McpCategory.DEVELOPMENT,
            commandOrUrl = "https://api.github.com/mcp",
            transportType = McpTransportType.SSE,
            iconName = "terminal",
            author = "Model Context Protocol"
        ),
        McpPreset(
            id = "filesystem-mcp",
            name = "Local Filesystem",
            description = "Read and manipulate local files with granular security permissions",
            category = McpCategory.SYSTEM,
            commandOrUrl = "http://localhost:3001/sse",
            transportType = McpTransportType.SSE,
            iconName = "folder",
            author = "Model Context Protocol"
        ),
        McpPreset(
            id = "postgres-mcp",
            name = "PostgreSQL Inspector",
            description = "Inspect database schema, run safe queries, and generate migrations",
            category = McpCategory.DATABASE,
            commandOrUrl = "http://localhost:3002/sse",
            transportType = McpTransportType.SSE,
            iconName = "dns",
            author = "Model Context Protocol"
        ),
        McpPreset(
            id = "puppeteer-mcp",
            name = "Puppeteer Browser",
            description = "Automate browser interactions, take screenshots, and scrape dynamic pages",
            category = McpCategory.BROWSER,
            commandOrUrl = "http://localhost:3003/sse",
            transportType = McpTransportType.SSE,
            iconName = "public",
            author = "Model Context Protocol"
        ),
        McpPreset(
            id = "fetch-mcp",
            name = "Fetch & Scrape",
            description = "Web page content extraction and markdown conversion",
            category = McpCategory.SEARCH,
            commandOrUrl = "http://localhost:3004/sse",
            transportType = McpTransportType.SSE,
            iconName = "download",
            author = "Model Context Protocol"
        ),
        McpPreset(
            id = "memory-mcp",
            name = "Knowledge Graph Memory",
            description = "Persistent graph-based memory across long conversations",
            category = McpCategory.PRODUCTIVITY,
            commandOrUrl = "http://localhost:3005/sse",
            transportType = McpTransportType.SSE,
            iconName = "psychology",
            author = "Model Context Protocol"
        )
    )

    val categories = listOf("All") + McpCategory.values().map { it.name }

    fun findById(id: String): McpPreset? = presets.find { it.id == id }

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
                it.category.name.lowercase().contains(q)
        }
    }
}
