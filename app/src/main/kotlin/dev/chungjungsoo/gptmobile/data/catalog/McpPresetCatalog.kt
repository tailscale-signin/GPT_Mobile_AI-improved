package dev.chungjungsoo.gptmobile.data.catalog

import kotlinx.serialization.Serializable

@Serializable
data class McpServerPreset(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val url: String,
    val transportType: String = "SSE",
    val headers: Map<String, String> = emptyMap(),
    val iconName: String = "extension",
    val author: String = "Community"
)

object McpPresetCatalog {
    val presets = listOf(
        McpServerPreset(
            id = "brave-search",
            name = "Brave Search",
            description = "Web and local search capabilities via Brave Search API",
            category = "Search",
            url = "https://api.search.brave.com/res/v1",
            transportType = "SSE",
            iconName = "travel_explore",
            author = "Brave Software"
        ),
        McpServerPreset(
            id = "github-mcp",
            name = "GitHub Integration",
            description = "Interact with GitHub repositories, issues, PRs, and commits",
            category = "Development",
            url = "https://api.github.com/mcp",
            transportType = "SSE",
            iconName = "terminal",
            author = "Model Context Protocol"
        ),
        McpServerPreset(
            id = "filesystem-mcp",
            name = "Local Filesystem",
            description = "Read and manipulate local files with granular security permissions",
            category = "System",
            url = "http://localhost:3001/sse",
            transportType = "SSE",
            iconName = "folder",
            author = "Model Context Protocol"
        ),
        McpServerPreset(
            id = "postgres-mcp",
            name = "PostgreSQL Inspector",
            description = "Inspect database schema, run safe queries, and generate migrations",
            category = "Database",
            url = "http://localhost:3002/sse",
            transportType = "SSE",
            iconName = "dns",
            author = "Model Context Protocol"
        ),
        McpServerPreset(
            id = "puppeteer-mcp",
            name = "Puppeteer Browser",
            description = "Automate browser interactions, take screenshots, and scrape dynamic pages",
            category = "Browser",
            url = "http://localhost:3003/sse",
            transportType = "SSE",
            iconName = "public",
            author = "Model Context Protocol"
        ),
        McpServerPreset(
            id = "fetch-mcp",
            name = "Fetch & Scrape",
            description = "Web page content extraction and markdown conversion",
            category = "Search",
            url = "http://localhost:3004/sse",
            transportType = "SSE",
            iconName = "download",
            author = "Model Context Protocol"
        ),
        McpServerPreset(
            id = "memory-mcp",
            name = "Knowledge Graph Memory",
            description = "Persistent graph-based memory across long conversations",
            category = "Productivity",
            url = "http://localhost:3005/sse",
            transportType = "SSE",
            iconName = "psychology",
            author = "Model Context Protocol"
        )
    )

    val categories = listOf("All", "Search", "Development", "System", "Database", "Browser", "Productivity")

    fun findById(id: String): McpServerPreset? = presets.find { it.id == id }

    fun filterByCategory(category: String): List<McpServerPreset> {
        return if (category == "All") presets else presets.filter { it.category.equals(category, ignoreCase = true) }
    }

    fun searchPresets(query: String): List<McpServerPreset> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return presets
        return presets.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }
    }
}
