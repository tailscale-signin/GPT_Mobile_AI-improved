package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterMessageItem
import java.util.concurrent.ConcurrentHashMap

data class CachedResponse(
    val content: String,
    val timestamp: Long
)

class ResponseCache(
    private val cacheTtlSeconds: Int = 300,
    private val callback: ((String) -> String?)? = null
) {
    private val cache = ConcurrentHashMap<String, CachedResponse>()

    fun get(promptHash: String): String? {
        val cached = cache[promptHash]
        if (cached != null && !isExpired(cached.timestamp)) {
            return cached.content
        }
        val fetched = callback?.invoke(promptHash)
        if (fetched != null) {
            put(promptHash, fetched)
        }
        return fetched
    }

    fun put(promptHash: String, content: String) {
        cache[promptHash] = CachedResponse(
            content = content,
            timestamp = System.currentTimeMillis()
        )
    }

    fun clear() {
        cache.clear()
    }

    fun size(): Int = cache.size

    private fun isExpired(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp > (cacheTtlSeconds * 1000L)
    }
}

object CacheKeyGenerator {
    fun generate(model: String, messages: List<OpenRouterMessageItem>): String {
        val promptText = messages.joinToString("\n") { "${it.role}:${it.content}" }
        return "${model}:${promptText.hashCode()}"
    }

    fun generate(model: String, prompt: String): String {
        return "${model}:${prompt.hashCode()}"
    }
}
