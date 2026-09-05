package dev.chungjungsoo.gptmobile.data.mcp

/**
 * Built-in tool definitions for the MCPSearch Android / Termux toolset.
 * Integrated from https://github.com/tailscale-signin/mcpsearch-installer-android-termux
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
    const val PRESET_ID = "mcpsearch-android-termux"
    const val DEFAULT_LAUNCHER_PATH = "~/.mcpsearch/run.sh"
    const val INSTALLER_SCRIPT_URL =
        "https://raw.githubusercontent.com/tailscale-signin/mcpsearch-installer-android-termux/main/install_mcpsearch.sh"
    const val INSTALL_COMMAND =
        "curl -fsSL -o ~/install_mcpsearch.sh $INSTALLER_SCRIPT_URL && bash ~/install_mcpsearch.sh"

    /**
     * Set of built-in tools provided by MCPSearch on Android / Termux.
     */
    val tools: List<McpBuiltinTool> = listOf(
        McpBuiltinTool(
            name = "search",
            description = "Multi-engine web search with AI summarization and async caching via hishel.",
            parameters = listOf(
                McpToolParameter(
                    name = "query",
                    type = "string",
                    description = "Search query or natural language question",
                    required = true
                ),
                McpToolParameter(
                    name = "max_results",
                    type = "integer",
                    description = "Maximum number of search results to retrieve",
                    required = false,
                    default = "5"
                )
            )
        ),
        McpBuiltinTool(
            name = "investigate",
            description = "Deep multi-source research agent across web search, news, and social platforms.",
            parameters = listOf(
                McpToolParameter(
                    name = "topic",
                    type = "string",
                    description = "Topic or hypothesis to thoroughly investigate",
                    required = true
                ),
                McpToolParameter(
                    name = "depth",
                    type = "string",
                    description = "Investigation depth: quick, normal, or deep",
                    required = false,
                    default = "normal"
                ),
                McpToolParameter(
                    name = "include_social",
                    type = "boolean",
                    description = "Whether to include Reddit and social discussions",
                    required = false,
                    default = "true"
                ),
                McpToolParameter(
                    name = "max_sources",
                    type = "integer",
                    description = "Maximum distinct sources to query and synthesize",
                    required = false,
                    default = "10"
                )
            )
        ),
        McpBuiltinTool(
            name = "compare",
            description = "Comparative analysis between multiple topics, technologies, or entities.",
            parameters = listOf(
                McpToolParameter(
                    name = "topics",
                    type = "string",
                    description = "Comma-separated topics or entities to compare",
                    required = true
                ),
                McpToolParameter(
                    name = "depth",
                    type = "string",
                    description = "Comparison depth level: quick, normal, or deep",
                    required = false,
                    default = "normal"
                )
            )
        ),
        McpBuiltinTool(
            name = "trending",
            description = "Discover trending topics, discussions, and repositories across GitHub, Reddit, and web.",
            parameters = listOf(
                McpToolParameter(
                    name = "platforms",
                    type = "string",
                    description = "Target platforms to check (e.g. 'github', 'reddit', 'all')",
                    required = false,
                    default = "all"
                )
            )
        ),
        McpBuiltinTool(
            name = "get_crawl_stats",
            description = "Retrieve crawler statistics, cache hit rates, and crawl status.",
            parameters = emptyList()
        )
    )
}
