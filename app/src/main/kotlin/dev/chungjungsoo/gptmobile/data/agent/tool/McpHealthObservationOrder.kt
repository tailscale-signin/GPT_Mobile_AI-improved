package dev.chungjungsoo.gptmobile.data.agent.tool

/** A response belongs to the configuration epoch and request that produced it. */
internal class McpHealthObservationOrder {
    data class Token(val connection: String, val key: String, val epoch: Long, val request: Long)
    private data class State(val key: String, val epoch: Long, var published: Long = 0)
    private val states = mutableMapOf<String, State>()
    private var sequence = 0L

    @Synchronized
    fun begin(connection: String, key: String): Token {
        val request = ++sequence
        val state = states[connection]?.takeIf { it.key == key }
            ?: State(key, request).also { states[connection] = it }
        return Token(connection, key, state.epoch, request)
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
