package dev.chungjungsoo.gptmobile.data.mcp

import android.content.Context
import com.google.gson.Gson
import dev.chungjungsoo.gptmobile.data.mcp.model.McpParameter
import dev.chungjungsoo.gptmobile.data.mcp.model.McpToolCategory
import dev.chungjungsoo.gptmobile.data.mcp.model.McpToolDefinition
import dev.chungjungsoo.gptmobile.data.mcp.model.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central registry of all available MCP tools with metadata and execution capabilities.
 */
class McpToolRegistry(
    private val context: Context,
    private val gson: Gson = Gson()
) {

    private val tools = mutableListOf<McpToolDefinition>()
    private val toolExecutors = mutableMapOf<String, suspend (Map<String, Any>) -> McpToolResult>()

    /**
     * Register a tool definition with its executor.
     */
    fun register(tool: McpToolDefinition, executor: suspend (Map<String, Any>) -> McpToolResult) {
        tools.add(tool)
        toolExecutors[tool.name] = executor
    }

    /**
     * Get all registered tools.
     */
    fun getTools(): List<McpToolDefinition> = tools.toList()

    /**
     * Get tools by category.
     */
    fun getToolsByCategory(category: String): List<McpToolDefinition> {
        return tools.filter { it.category == category }
    }

    /**
     * Search tools by name, description, or parameter.
     */
    fun searchTools(query: String): List<McpToolDefinition> {
        val lowerQuery = query.lowercase()
        return tools.filter {
            it.name.contains(lowerQuery) ||
                    it.description.contains(lowerQuery) ||
                    it.parameters.any { p -> p.name.contains(lowerQuery) }
        }
    }

    /**
     * Get a tool by name.
     */
    fun getToolByName(name: String): McpToolDefinition? {
        return tools.find { it.name == name }
    }

    /**
     * Execute a tool by name.
     */
    suspend fun execute(toolName: String, params: Map<String, Any>): McpToolResult {
        return withContext(Dispatchers.IO) {
            val executor = toolExecutors[toolName] ?: return@withContext McpToolResult.failure("Tool not found: $toolName")
            try {
                executor(params)
            } catch (e: Exception) {
                McpToolResult.failure(e.message ?: "Execution failed")
            }
        }
    }

    /**
     * Get available tool categories.
     */
    fun getCategories(): Set<String> = tools.map { it.category }.toSet()

    companion object {
        /**
         * Default high-priority tools to register.
         */
        val defaultTools = listOf(
            // File System Tools
            McpToolDefinition(
                name = "read_file",
                description = "Read content from a local file",
                parameters = listOf(
                    McpParameter(name = "path", type = "string", description = "File path to read", required = true)
                ),
                category = McpToolCategory.FILE_SYSTEM.name
            ),
            McpToolDefinition(
                name = "write_file",
                description = "Write content to a local file",
                parameters = listOf(
                    McpParameter(name = "path", type = "string", description = "File path", required = true),
                    McpParameter(name = "content", type = "string", description = "Content to write", required = true)
                ),
                category = McpToolCategory.FILE_SYSTEM.name
            ),
            McpToolDefinition(
                name = "list_directory",
                description = "List contents of a directory",
                parameters = listOf(
                    McpParameter(name = "path", type = "string", description = "Directory path", required = true)
                ),
                category = McpToolCategory.FILE_SYSTEM.name
            ),
            McpToolDefinition(
                name = "delete_file",
                description = "Delete a file (requires confirmation)",
                parameters = listOf(
                    McpParameter(name = "path", type = "string", description = "File path to delete", required = true)
                ),
                category = McpToolCategory.FILE_SYSTEM.name
            ),

            // Code Execution Tools
            McpToolDefinition(
                name = "execute_code",
                description = "Execute Python/JavaScript code snippets",
                parameters = listOf(
                    McpParameter(name = "code", type = "string", description = "Code to execute", required = true),
                    McpParameter(name = "language", type = "string", description = "Programming language (python, javascript)", required = false)
                ),
                category = McpToolCategory.CODE_EXECUTION.name
            ),

            // Database Tools
            McpToolDefinition(
                name = "query_database",
                description = "Query SQLite/Room database",
                parameters = listOf(
                    McpParameter(name = "sql", type = "string", description = "SQL query to execute", required = true)
                ),
                category = McpToolCategory.DATABASE.name
            ),

            // Network Tools
            McpToolDefinition(
                name = "http_request",
                description = "Make HTTP requests (GET/POST/PUT/DELETE)",
                parameters = listOf(
                    McpParameter(name = "url", type = "string", description = "Target URL", required = true),
                    McpParameter(name = "method", type = "string", description = "HTTP method (GET, POST, PUT, DELETE)", required = false, defaultValue = "GET"),
                    McpParameter(name = "headers", type = "object", description = "Request headers", required = false),
                    McpParameter(name = "body", type = "string", description = "Request body", required = false)
                ),
                category = McpToolCategory.NETWORK.name
            ),

            // Translation Tools
            McpToolDefinition(
                name = "translate_text",
                description = "Translate text between languages",
                parameters = listOf(
                    McpParameter(name = "text", type = "string", description = "Text to translate", required = true),
                    McpParameter(name = "source_lang", type = "string", description = "Source language code (e.g., en, es)", required = false),
                    McpParameter(name = "target_lang", type = "string", description = "Target language code (e.g., fr, de)", required = true)
                ),
                category = McpToolCategory.TRANSLATION.name
            )
        )
    }
}
