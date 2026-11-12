package dev.chungjungsoo.gptmobile.data.agent.tool

/** A response belongs to the configuration epoch and request that produced it. */
internal class McpHealthObservationOrder {
    data class Token(
        val connection: String,
        val key: String,
        val epoch: Long,
        val request: Long,
        val configurationRevision: String? = null,
        /** Created before dispatch; shared by transport and MCP terminal diagnostics. */
        val operationId: String = java.util.UUID.randomUUID().toString().replace("-", "")
    )
    private data class State(val key: String, val epoch: Long, val configurationRevision: String?, var published: Long = 0)
    private val states = mutableMapOf<String, State>()
    private var sequence = 0L

    @Synchronized
    fun begin(connection: String, key: String, configurationRevision: String? = null): Token {
        val request = ++sequence
        val state = states[connection]?.takeIf { it.key == key && it.configurationRevision == configurationRevision }
            ?: State(key, request, configurationRevision).also { states[connection] = it }
        return Token(connection, key, state.epoch, request, configurationRevision)
    }

    @Synchronized
    fun publish(token: Token, action: () -> Unit) {
        val state = states[token.connection] ?: return
        if (state.key != token.key || state.epoch != token.epoch || token.request < state.published) return
        state.published = token.request
        action()
    }

    @Synchronized
    fun invalidate(connection: String) {
        states.remove(connection)
    }

    @Synchronized
    fun invalidateAll(): Set<String> = states.keys.toSet().also { states.clear() }
}
