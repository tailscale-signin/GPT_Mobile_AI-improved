package dev.chungjungsoo.gptmobile.data.mcp.model

/**
 * Categories for MCP tools.
 */
enum class McpToolCategory(val displayName: String) {
    FILE_SYSTEM("File System"),
    CODE_EXECUTION("Code Execution"),
    DATABASE("Database"),
    NETWORK("Network"),
    TRANSLATION("Translation")
}