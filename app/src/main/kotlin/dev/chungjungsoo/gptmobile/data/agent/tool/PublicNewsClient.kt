package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Fixed public origins, bounded reads, no provider credentials or diagnostic body logger. */
@Singleton
class PublicNewsClient internal constructor(private val http: HttpClient) {
    @Inject constructor() : this(
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout)
        }
    )

    suspend fun google(action: String, query: String, country: String, language: String, limit: Int): List<JsonObject> {
        val endpoint = if (action == "top") "https://news.google.com/rss" else "https://news.google.com/rss/search"
        val params = mapOf("hl" to language, "gl" to country, "ceid" to "$country:$language") +
            if (action == "top") emptyMap() else mapOf("q" to query)
        return feed(read(endpoint, params), false).take(limit)
    }

    suspend fun trends(country: String, limit: Int) = feed(read("https://trends.google.com/trending/rss", mapOf("geo" to country)), true).take(limit)

    suspend fun hacker(query: String, type: String, limit: Int): List<JsonObject> {
        require(type in setOf("top", "new", "ask", "show", "jobs"))
        if (query.isNotBlank()) {
            val tag = when (type) {
                "ask" -> "ask_hn"
                "show" -> "show_hn"
                "jobs" -> "job"
                else -> "story"
            }
            val root = Json.parseToJsonElement(read("https://hn.algolia.com/api/v1/search_by_date", mapOf("query" to query, "tags" to tag, "hitsPerPage" to limit.toString()))) as JsonObject
            return (root["hits"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { item ->
                buildJsonObject {
                    put("title", item["title"] ?: JsonPrimitive("Hacker News"))
                    put("url", item["url"]?.takeUnless { it.toString() == "null" } ?: JsonPrimitive("https://news.ycombinator.com/item?id=${text(item, "objectID")}"))
                    put("publisher", "Hacker News")
                    put("publishedAt", item["created_at"] ?: JsonPrimitive("Unknown"))
                    item["points"]?.let { put("points", it) }
                    item["num_comments"]?.let { put("commentCount", it) }
                }
            }
        }
        val endpoint = "https://hacker-news.firebaseio.com/v0/${type}stories.json"
        val ids = Json.parseToJsonElement(read(endpoint)) as JsonArray
        return coroutineScope {
            ids.take(limit).map { id ->
                async {
                    try {
                        val item = Json.parseToJsonElement(read("https://hacker-news.firebaseio.com/v0/item/${(id as JsonPrimitive).content}.json")) as? JsonObject
                            ?: return@async null
                        buildJsonObject {
                            put("title", item["title"] ?: JsonPrimitive("Hacker News"))
                            put("url", item["url"]?.takeUnless { it.toString() == "null" } ?: JsonPrimitive("https://news.ycombinator.com/item?id=${id.content}"))
                            put("publisher", "Hacker News")
                            (item["time"] as? JsonPrimitive)?.longOrNull?.let { put("publishedAt", Instant.ofEpochSecond(it).toString()) }
                            item["score"]?.let { put("points", it) }
                            item["descendants"]?.let { put("commentCount", it) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    internal fun feed(xml: String, trends: Boolean): List<JsonObject> = Jsoup.parse(xml, "", Parser.xmlParser()).select("item").take(30).map { item ->
        buildJsonObject {
            put("title", item.selectFirst("title")?.text().orEmpty().take(500))
            put("url", item.selectFirst("link")?.text().orEmpty().take(2048))
            put("publisher", item.selectFirst("source")?.text() ?: if (trends) "Google Trends" else "Google News")
            put("publishedAt", item.selectFirst("pubDate")?.text() ?: "Unknown")
            put("summary", Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text().take(1000))
            if (trends) put("traffic", item.getElementsByTag("ht:approx_traffic").first()?.text().orEmpty())
        }
    }.filter { (it["title"] as? JsonPrimitive)?.content?.isNotBlank() == true }

    private suspend fun read(url: String, parameters: Map<String, String> = emptyMap()): String = http.prepareGet(url) {
        header(HttpHeaders.Accept, "application/json,application/rss+xml,application/xml,text/xml")
        parameters.forEach { (name, value) -> parameter(name, value) }
        timeout {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 8_000
        }
    }.execute { response ->
        check(response.status.value == 200) { "News feed unavailable." }
        check((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= 1_048_576)
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            check(output.size() + count <= 1_048_576)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }

    private fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
