package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Safe response shape and cache provenance; never contains prompt, answer or reasoning text. */
@Serializable
data class FreeResponseObservation(
    val format: String,
    val resolvedModel: String? = null,
    val choiceCount: Int = 0,
    val visibleCharacters: Int = 0,
    val reasoningCharacters: Int = 0,
    val toolCallFragmentCount: Int = 0,
    val finishReasons: List<String> = emptyList(),
    val discardedChunks: Int = 0,
    val cacheStatus: String? = null,
    val cacheIdentityDigest: String? = null,
    val cacheDate: String? = null,
    val cacheAgeSeconds: Long? = null,
    val repeatedUnusableCache: Boolean = false
) {
    fun withChunk(chunk: ChatCompletionChunk): FreeResponseObservation = copy(
        resolvedModel = chunk.model?.take(256) ?: resolvedModel,
        choiceCount = maxOf(choiceCount, chunk.choices.orEmpty().size),
        visibleCharacters = visibleCharacters + chunk.choices.orEmpty().sumOf { it.effectiveDelta.content.orEmpty().length },
        reasoningCharacters = reasoningCharacters + chunk.choices.orEmpty().sumOf { it.effectiveDelta.effectiveReasoning.orEmpty().length },
        toolCallFragmentCount = toolCallFragmentCount + chunk.choices.orEmpty().sumOf { it.effectiveDelta.toolCalls.orEmpty().size },
        finishReasons = (finishReasons + chunk.choices.orEmpty().mapNotNull { it.finishReason?.take(40) }).distinct().take(8)
    )

    companion object {
        fun fromHeaders(format: String, header: (String) -> String?): FreeResponseObservation {
            val cache = header("X-Cache")?.substringBefore(',')?.trim()?.uppercase()?.takeIf { it in setOf("HIT", "MISS", "BYPASS", "STALE", "EXPIRED", "REVALIDATED") }
            val identity = listOf("X-Cache-Key", "X-Pollinations-Cache-Key", "ETag").firstNotNullOfOrNull { header(it)?.take(2048) }
            val date = header("Date")?.take(80)?.takeIf { runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess }
            return FreeResponseObservation(format, cacheStatus = cache, cacheIdentityDigest = identity?.let(::responseDigest), cacheDate = date, cacheAgeSeconds = header("Age")?.toLongOrNull()?.takeIf { it >= 0 })
        }
    }
}

internal fun responseDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Recognize identical unusable cache hits; never adds cache-busting parameters or retries. */
internal class UnusableResponseCache(private val now: () -> Long = { System.currentTimeMillis() }) {
    private data class Entry(val identity: String, val seenAt: Long)
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized fun observe(requestKey: String, responseBody: String, observation: FreeResponseObservation, usable: Boolean): FreeResponseObservation {
        val current = now()
        entries.entries.removeAll { current - it.value.seenAt !in 0..RETENTION_MILLIS }
        if (usable) {
            entries.remove(requestKey)
            return observation
        }
        val identity = observation.cacheIdentityDigest ?: responseDigest(responseBody)
        val repeated = observation.cacheStatus == "HIT" && entries[requestKey]?.identity == identity
        entries[requestKey] = Entry(identity, current)
        while (entries.size > 128) entries.remove(entries.keys.first())
        return observation.copy(cacheIdentityDigest = identity, repeatedUnusableCache = repeated)
    }

    companion object {
        private const val RETENTION_MILLIS = 5 * 60_000L
        val shared = UnusableResponseCache()
    }
}
