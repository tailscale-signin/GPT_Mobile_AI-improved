package dev.chungjungsoo.gptmobile.data.mcp.model

/**
 * Definition of an MCP tool with metadata and parameters.
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<McpParameter>,
    val category: String
)