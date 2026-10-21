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
