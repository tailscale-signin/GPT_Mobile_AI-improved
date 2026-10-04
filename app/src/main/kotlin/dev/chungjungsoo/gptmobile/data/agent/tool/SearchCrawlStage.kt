package dev.chungjungsoo.gptmobile.data.agent.tool

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One turn-wide cache and page allowance; readers are alternatives, not duplicate jobs. */
internal class SearchCrawlStage(
    private val crawlers: List<ResolvedAgentTool>,
    private val maxPages: Int,
    private val stillEnabled: () -> Boolean = { true }
) {
    private val cache = ConcurrentHashMap<String, JsonObject>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val attemptedPages = AtomicInteger()
    private val permits = Semaphore(2)

    suspend fun execute(callId: String, sources: List<JsonObject>): JsonObject = coroutineScope {
        val urls = sources.mapNotNull { (it["url"] as? JsonPrimitive)?.contentOrNull }
            .filter { runCatching { URI(it).let { uri -> uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null } }.getOrDefault(false) }
            .distinctBy(::canonicalSearchUrl).take(maxPages.coerceIn(0, 20))
        val pages = urls.mapIndexed { index, url ->
            async {
                val key = canonicalSearchUrl(url)
                locks.computeIfAbsent(key) { Mutex() }.withLock {
                    cache[key]?.let { return@withLock it }
                    if (!stillEnabled() || !reservePage()) return@withLock null
                    val page = permits.withPermit { read("$callId:crawl:$index", url) }
                    cache[key] = page
                    page
                }
            }
        }.awaitAll().filterNotNull()
        buildJsonObject {
            put("owner", "search")
            put("requestedPages", urls.size)
            put("attemptedPages", attemptedPages.get().coerceAtMost(maxPages.coerceAtLeast(0)))
            put("completedPages", pages.count { it["status"] == JsonPrimitive("completed") })
            put("results", JsonArray(pages))
            if (crawlers.isEmpty()) put("notice", "No selected crawler is available for this profile.")
        }
    }

    private fun reservePage(): Boolean {
        val limit = maxPages.coerceIn(0, 20)
        while (true) {
            val used = attemptedPages.get()
            if (used >= limit) return false
            if (attemptedPages.compareAndSet(used, used + 1)) return true
        }
    }

    private suspend fun read(callId: String, url: String): JsonObject {
        for (reader in crawlers) {
            val arguments = crawlerArguments(reader.tool.definition, url) ?: continue
            val result = try {
                withTimeoutOrNull(30_000) { reader.tool.execute(callId + ":" + reader.modelToolName, arguments) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue
            if (result.toolCallBudgetExhausted || result.outputBudgetExhausted) break
            if (result.isError) continue
            val payload = result.content.researchPayload()
            val text = pageText(payload).ifBlank { result.content.researchText() }
            if (text.isBlank() || looksLikeConsentOrScriptShell(text)) continue
            return buildJsonObject {
                put("url", url)
                put("crawler", reader.realToolName)
                put("status", "completed")
                put("content", dev.chungjungsoo.gptmobile.data.agent.truncateUtf8(text, 6000))
                put("truncated", text.toByteArray().size > 6000 || (payload as? JsonObject)?.get("truncated") == JsonPrimitive(true))
            }
        }
        return buildJsonObject {
            put("url", url)
            put("status", "failed")
            put("content", "No enabled reader returned usable page evidence.")
        }
    }
}
