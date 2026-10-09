package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    suspend fun google(action: String, query: String, country: String, language: String, limit: Int): NewsFeedResult = boundedFeed { diagnostics ->
        val endpoint = if (action == "top") "https://news.google.com/rss" else "https://news.google.com/rss/search"
        val params = mapOf("hl" to language, "gl" to country, "ceid" to "$country:${language.substringBefore('-')}") +
            if (action == "top") emptyMap() else mapOf("q" to query)
        val primary = fetchFeed(endpoint, params, "google", false, limit, diagnostics)
        if (primary.isNotEmpty()) return@boundedFeed NewsFeedResult(primary, diagnostics)
        // An alternate provider is explicit in both diagnostics and every returned article.
        val fallback = fetchFeed(
            "https://www.bing.com/news/search",
            mapOf("q" to if (action == "top") "" else query, "format" to "rss", "setmkt" to "${language.substringBefore('-')}-$country"),
            "bing",
            false,
            limit,
            diagnostics
        )
        NewsFeedResult(fallback, diagnostics, fallbackUsed = fallback.isNotEmpty())
    }

    suspend fun trends(country: String, limit: Int, includeRelated: Boolean = true): NewsFeedResult = boundedFeed { diagnostics ->
        val articles = fetchFeed("https://trends.google.com/trending/rss", mapOf("geo" to country), "trends", true, limit, diagnostics)
            .map { article ->
                JsonObject(
                    article + mapOf(
                        "url" to JsonPrimitive("https://trends.google.com/trends/explore?geo=$country&q=${URLEncoder.encode(text(article, "title"), "UTF-8")}"),
                        "country" to JsonPrimitive(country)
                    )
                ).let { if (includeRelated) it else JsonObject(it - "relatedNews" - "relatedQueries") }
            }
        NewsFeedResult(articles, diagnostics)
    }

    private suspend fun boundedFeed(request: suspend (MutableList<JsonObject>) -> NewsFeedResult): NewsFeedResult {
        val diagnostics = mutableListOf<JsonObject>()
        return withTimeoutOrNull(45_000) { request(diagnostics) } ?: NewsFeedResult(
            emptyList(),
            diagnostics + buildJsonObject {
                put("status", "error")
                put("errorCode", "deadline_exceeded")
            }
        )
    }

    private suspend fun fetchFeed(
        endpoint: String,
        params: Map<String, String>,
        provider: String,
        trends: Boolean,
        limit: Int,
        diagnostics: MutableList<JsonObject>
    ): List<JsonObject> {
        val result = withTimeoutOrNull(20_000) {
            val url = URLBuilder(endpoint).apply { params.forEach { (key, value) -> parameters.append(key, value) } }.buildString()
            for (attempt in 1..3) {
                val startedAt = Instant.now().toString()
                try {
                    var headers = emptyMap<String, String>()
                    val body = read(url, responseMetadata = { headers = it })
                    val articles = withContext(Dispatchers.Default) { feed(body, trends) }.take(limit)
                    diagnostics += buildJsonObject {
                        put("provider", provider)
                        put("endpoint", url)
                        put("attempt", attempt)
                        put("httpStatus", 200)
                        headers["effectiveEndpoint"]?.let { put("effectiveEndpoint", it) }
                        put("status", if (articles.isEmpty()) "empty" else "ok")
                        put("startedAt", startedAt)
                        put("retrievedAt", Instant.now().toString())
                        headers["Age"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { put("cacheAgeSeconds", it) }
                        put("cacheAgeKnown", headers["Age"]?.toLongOrNull()?.let { it >= 0 } == true)
                        headers["Date"]?.let { put("serverDate", it.take(100)) }
                        headers["Last-Modified"]?.let { put("lastModified", it.take(100)) }
                        put("appCache", false)
                        put("resultCount", articles.size)
                    }
                    return@withTimeoutOrNull articles.map { JsonObject(it + mapOf("feedProvider" to JsonPrimitive(provider), "sourceFeedUrl" to JsonPrimitive(url), "retrievedAt" to JsonPrimitive(Instant.now().toString()))) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val feedFailure = failure as? NewsFeedException
                    diagnostics += buildJsonObject {
                        put("provider", provider)
                        put("endpoint", url)
                        put("attempt", attempt)
                        put("startedAt", startedAt)
                        put("retrievedAt", Instant.now().toString())
                        put("status", "error")
                        put("errorCode", feedFailure?.code ?: "network_error")
                        feedFailure?.status?.let { put("httpStatus", it) }
                    }
                    val retryable = feedFailure?.retryable ?: (failure is IOException || failure is HttpRequestTimeoutException)
                    if (!retryable || attempt == 3) return@withTimeoutOrNull emptyList<JsonObject>()
                    delay(feedFailure?.retryDelayMillis ?: (250L * (1L shl (attempt - 1))))
                }
            }
            emptyList<JsonObject>()
        }
        if (result != null) return result
        diagnostics += buildJsonObject {
            put("provider", provider)
            put("endpoint", URLBuilder(endpoint).apply { params.forEach { (key, value) -> parameters.append(key, value) } }.buildString())
            put("status", "error")
            put("errorCode", "deadline_exceeded")
        }
        return emptyList()
    }

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

    internal fun feed(xml: String, trends: Boolean): List<JsonObject> {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        if (document.selectFirst("rss > channel") == null) throw NewsFeedException("invalid_feed", 200)
        return document.select("channel > item").take(30).map { item ->
            val related = item.getElementsByTag("ht:news_item").take(5).mapNotNull { news ->
                val title = news.getElementsByTag("ht:news_item_title").first()?.text().orEmpty()
                val url = safeUrl(news.getElementsByTag("ht:news_item_url").first()?.text().orEmpty())
                if (title.isBlank() || url.isBlank()) {
                    null
                } else {
                    buildJsonObject {
                        put("title", title.take(500))
                        put("url", url)
                        put("publisher", news.getElementsByTag("ht:news_item_source").first()?.text() ?: "Unknown")
                        put("summary", Jsoup.parse(news.getElementsByTag("ht:news_item_snippet").first()?.text().orEmpty()).text().take(1000))
                    }
                }
            }
            val description = Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text().take(1000)
            buildJsonObject {
                put("title", item.selectFirst("title")?.text().orEmpty().take(500))
                put("url", safeUrl(item.selectFirst("link")?.text().orEmpty()))
                put("publisher", item.selectFirst("source")?.text() ?: item.getElementsByTag("News:Source").first()?.text() ?: "Unknown")
                put("publishedAt", item.selectFirst("pubDate")?.text() ?: "Unknown")
                put("summary", description.ifBlank { related.joinToString("; ") { text(it, "title") }.take(1000) })
                put(
                    "summaryKind",
                    if (description.isNotBlank()) {
                        "feed_description"
                    } else if (related.isNotEmpty()) {
                        "related_headlines"
                    } else {
                        "unavailable"
                    }
                )
                if (trends) {
                    put("publisher", "Google Trends")
                    put("traffic", item.getElementsByTag("ht:approx_traffic").first()?.text().orEmpty())
                    put("trafficIsApproximate", true)
                    put("relatedNews", JsonArray(related))
                    put("relatedQueries", buildJsonArray { })
                    put("relatedQueriesAvailable", false)
                }
            }
        }.filter { text(it, "title").isNotBlank() && (trends || text(it, "url").isNotBlank()) }
    }

    private fun safeUrl(raw: String): String = runCatching {
        var url = Url(raw.take(2048))
        if (url.host == "www.bing.com" && url.encodedPath == "/news/apiclick.aspx") {
            url = Url(url.parameters["url"] ?: return@runCatching "")
        }
        url.toString().takeIf { url.protocol.name in setOf("https", "http") && url.host.isNotBlank() && url.user.isNullOrBlank() && url.password.isNullOrBlank() }.orEmpty()
    }.getOrDefault("")

    private suspend fun read(
        url: String,
        parameters: Map<String, String> = emptyMap(),
        responseMetadata: (Map<String, String>) -> Unit = { }
    ): String {
        var target = URLBuilder(url).apply { parameters.forEach { (key, value) -> this.parameters.append(key, value) } }.buildString()
        val host = Url(target).host
        repeat(4) { hop ->
            var redirect: String? = null
            val body = http.prepareGet(target) {
                header(HttpHeaders.Accept, "application/json,application/rss+xml,application/xml,text/xml")
                header(HttpHeaders.UserAgent, "GPTMobile/1.0 (public news RSS reader)")
                timeout {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 5_000
                }
            }.execute { response ->
                val status = response.status.value
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = response.headers[HttpHeaders.Location] ?: throw NewsFeedException("redirect_missing", status)
                    val next = Url(URI(target).resolve(location).toString())
                    if (hop == 3 || next.protocol.name != "https" || next.host != host || next.port != 443 || !next.user.isNullOrBlank() || !next.password.isNullOrBlank()) {
                        throw NewsFeedException("redirect_blocked", status)
                    }
                    redirect = next.toString()
                    return@execute ""
                }
                if (status != 200) {
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.let { value ->
                        value.toLongOrNull()?.coerceAtLeast(0) ?: runCatching {
                            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - Instant.now().epochSecond).coerceAtLeast(0)
                        }.getOrNull()
                    }
                    // Long provider cooldowns go straight to fallback rather than retrying too early.
                    throw NewsFeedException(
                        "http_error",
                        status,
                        (status == 429 || status in setOf(500, 502, 503, 504)) && (retryAfter == null || retryAfter <= 2),
                        retryAfter?.coerceAtMost(2)?.times(1000)
                    )
                }
                responseMetadata(listOf("Age", "Date", "Last-Modified").mapNotNull { key -> response.headers[key]?.let { key to it } }.toMap() + ("effectiveEndpoint" to target))
                if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 1_048_576) throw NewsFeedException("body_too_large", status)
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                val channel = response.bodyAsChannel()
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw NewsFeedException("body_too_large", status)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            if (redirect == null) return body
            target = redirect!!
        }
        throw NewsFeedException("redirect_blocked", null)
    }

    private fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

internal class NewsFeedException(
    val code: String,
    val status: Int?,
    val retryable: Boolean = false,
    val retryDelayMillis: Long? = null
) : IOException(code)
