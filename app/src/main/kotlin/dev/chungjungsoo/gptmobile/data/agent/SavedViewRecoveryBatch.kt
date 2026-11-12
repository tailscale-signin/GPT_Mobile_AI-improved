package dev.chungjungsoo.gptmobile.data.agent

import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/** A local saved-page suffix can follow a real pending batch without replacing its identity. */
internal object SavedViewRecoveryBatch {
    private val tool = Regex("github_[A-Za-z0-9_]+(?:__[A-Za-z0-9_]+)?")
    private val cursor = Regex("[^:]{1,256}:[0-9]{1,10}:[0-9]{1,10}")

    fun isComplete(exchange: AgentToolExchange, pendingIds: Set<String> = emptySet()): Boolean {
        val calls = exchange.calls
        val ids = calls.map { it.callId }
        val results = exchange.results.map { it.callId }
        return calls.isNotEmpty() &&
            ids.distinct().size == ids.size &&
            results.distinct().size == results.size &&
            ids.toSet() == results.toSet() &&
            calls.all { it.callId !in pendingIds && isLocalRead(it) }
    }

    private fun isLocalRead(call: ProviderEvent.ToolCall): Boolean {
        if (!tool.matches(call.name) || call.arguments.keys != setOf("view_cursor")) return false
        val value = (call.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (!cursor.matches(value) || value.any { it.isWhitespace() || it == '\u0085' }) return false
        val identity = MessageDigest.getInstance("SHA-256").digest("${call.name}:$value".toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return call.callId == "saved-result-consume-$identity"
    }
}
