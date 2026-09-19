package dev.chungjungsoo.gptmobile.data.mcp.model

/**
 * Parameter definition for an MCP tool.
 */
data class McpParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null
)