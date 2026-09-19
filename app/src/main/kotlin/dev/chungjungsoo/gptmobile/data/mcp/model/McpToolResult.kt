package dev.chungjungsoo.gptmobile.data.mcp.model

/**
 * Represents the result of an MCP tool execution.
 */
data class McpToolResult(
    val isSuccess: Boolean,
    val data: Any? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun success(data: Any? = null): McpToolResult = McpToolResult(
            isSuccess = true,
            data = data,
            errorMessage = null
        )

        fun failure(errorMessage: String): McpToolResult = McpToolResult(
            isSuccess = false,
            data = null,
            errorMessage = errorMessage
        )
    }
}
