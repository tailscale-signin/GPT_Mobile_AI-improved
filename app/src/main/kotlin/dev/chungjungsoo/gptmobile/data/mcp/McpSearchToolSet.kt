package dev.chungjungsoo.gptmobile.data.mcp

/**
 * Built-in tool definitions for Online Search via droid-mcp-web (native Android web search and fetch).
 * Integrated from https://github.com/stixez/droid-mcp (droid-mcp-web)
 * Preinstalled and enabled by default for all models unless explicitly turned off.
 */
data class McpToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null
)

data class McpBuiltinTool(
    val name: String,
    val description: String,
    val parameters: List<McpToolParameter> = emptyList()
)

object McpSearchToolSet {
    const val PRESET_ID = "droid-mcp-web"
    const val PRESET_NAME = "Online Search"
    const val REPOSITORY_URL = "https://github.com/stixez/droid-mcp"
    const val DEFAULT_LAUNCHER_PATH = "droid-mcp-web"
    const val IS_PREINSTALLED = true

    /**
     * Set of built-in tools provided by droid-mcp-web on Android.
     * Powered by DuckDuckGo and lightweight webpage extraction, requiring only INTERNET permission.
     */
    val tools: List<McpBuiltinTool> = listOf(
        McpBuiltinTool(
            name = "web_search",
            description = "Search the web using DuckDuckGo. Returns relevant search results with titles, snippets, and URLs.",
            parameters = listOf(
                McpToolParameter(
                    name = "query",
                    type = "string",
                    description = "Search query or question to find online information",
                    required = true
                ),
                McpToolParameter(
                    name = "num_results",
                    type = "integer",
                    description = "Number of search results to return (1-20)",
                    required = false,
                    default = "5"
                )
            )
        ),
        McpBuiltinTool(
            name = "fetch_webpage",
            description = "Fetch and extract readable plain text content from a web page URL.",
            parameters = listOf(
                McpToolParameter(
                    name = "url",
                    type = "string",
                    description = "Full HTTP or HTTPS URL of the webpage to fetch",
                    required = true
                ),
                McpToolParameter(
                    name = "raw",
                    type = "boolean",
                    description = "If true, returns raw HTML content instead of parsed text",
                    required = false,
                    default = "false"
                )
            )
        )
    )
}
