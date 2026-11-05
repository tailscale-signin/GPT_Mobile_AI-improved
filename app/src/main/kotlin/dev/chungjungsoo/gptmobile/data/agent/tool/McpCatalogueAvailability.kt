package dev.chungjungsoo.gptmobile.data.agent.tool

/** Knowing a schema does not prove that its remote execution endpoint is currently usable. */
data class McpCatalogueAvailability(
    val schemaKnown: Boolean,
    val authorized: Boolean?,
    val connectionHealthy: Boolean,
    val executionAvailable: Boolean,
    val refreshing: Boolean,
    val observedAtMonotonicMs: Long?
)

/** Emitted only by local catalogue checks before any tools/call request is made. */
internal class McpLocalToolRejection(val code: String, message: String) : IllegalStateException(message)

/** Validate before publishing a successful discovery receipt; a malformed list must never be ready. */
internal fun validateMcpCatalogue(tools: List<io.modelcontextprotocol.kotlin.sdk.types.Tool>): Int {
    val bytes = tools.sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }
    require(tools.size <= 2048 && bytes <= 4 * 1024 * 1024) { "MCP catalogue exceeds metadata bounds." }
    require(tools.all { it.name.isNotBlank() }) { "MCP tool names must not be blank." }
    require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate MCP tool names." }
    return bytes.toInt()
}
