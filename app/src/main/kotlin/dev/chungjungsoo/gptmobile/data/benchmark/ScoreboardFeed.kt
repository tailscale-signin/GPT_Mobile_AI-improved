package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

internal data class ScoreboardFeedSnapshot(val raw: String, val index: ScoreboardIndex, val etag: String?)

/** Anonymous static reads avoid sharing the GitHub REST API's per-IP rate limit. */
internal class ScoreboardFeed(private val client: HttpClient) {
    suspend fun fetch(saved: ScoreboardFeedSnapshot?): ScoreboardFeedSnapshot {
        val response = client.get(URL) {
            header(HttpHeaders.Accept, "application/json")
            // A new refresh revalidates the CDN copy; its ETag belongs only to this URL.
            header(HttpHeaders.CacheControl, "no-cache")
            saved?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        return when (response.status.value) {
            200 -> {
                val raw = response.boundedMetadata(ScoreboardRepository.MAX_INDEX_BYTES)
                ScoreboardFeedSnapshot(raw, decode(raw), response.headers[HttpHeaders.ETag])
            }
            304 -> saved ?: throw IOException("The server returned no data. Refresh the scoreboard again.")
            404 -> throw IOException("The community scoreboard feed is unavailable. Its publishing service may still need activation. Saved results remain available.")
            401, 403 -> throw IOException("The public scoreboard feed cannot be accessed. Check the connection or repository access; saved results remain available.")
            429 -> throw IOException("The scoreboard host is limiting requests. Try again later; saved results remain available.")
            else -> throw IOException("Could not refresh the community scoreboard (HTTP ${response.status.value}).")
        }
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/${ScoreboardRepository.REPOSITORY}/scoreboard-data/scoreboards/index.json"
        private val json = Json { ignoreUnknownKeys = false }

        fun decode(raw: String): ScoreboardIndex {
            check(raw.toByteArray().size <= ScoreboardRepository.MAX_INDEX_BYTES) { "The shared scoreboard exceeds the size limit." }
            val document = json.parseToJsonElement(raw).jsonObject
            check(document.keys.containsAll(listOf("schemaVersion", "updatedAt", "entries"))) { "The shared scoreboard is missing its index fields." }
            val index = json.decodeFromJsonElement<ScoreboardIndex>(document)
            check(index.schemaVersion == 1 && index.updatedAt in 0..253402300799999L && index.entries.size <= 1000) { "This scoreboard needs a newer app." }
            check(index.entries.all { BenchmarkScoreboard.evaluate(it) != null }) { "The shared scoreboard contains an invalid result." }
            check(index.entries.map { it.key }.distinct().size == index.entries.size) { "The shared scoreboard contains duplicate models." }
            return index
        }
    }
}
