package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A single News plugin. Public feeds remain usable when an optional MCP provider lacks a key. */
internal class NewsTool(
    private val client: PublicNewsClient,
    private val allowed: suspend () -> Boolean,
    private val defaults: () -> Pair<String, String> = { "CA" to "en" }
) : AgentTool {
    override val definition = AgentToolDefinition(
        "news",
        "News plugin: Google News search/top/topic/location with labeled Bing RSS fallback, Google Trends, and Hacker News. No API key for public feeds. " +
            "Use source=all to combine Google News and Hacker News in one bounded call. Return dates and publishers; trends are interest signals, not verified news. Country/geo override the configured region. related_queries toggles available related context; RSS related-query metrics remain unavailable. Diagnostics distinguish empty feeds from outages and report cache age. Do not repeatedly search the same query.",
        Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["search","top","topic","location","trending","hacker"],"default":"search"},"query":{"type":"string","maxLength":250},"source":{"type":"string","enum":["all","google","hacker"],"default":"all"},"country":{"type":"string","pattern":"^[A-Za-z]{2}$"},"geo":{"type":"string","pattern":"^[A-Za-z]{2}$"},"related_queries":{"type":"boolean","default":true},"language":{"type":"string","pattern":"^[a-z]{2}(-[A-Z]{2})?$"},"type":{"type":"string","enum":["top","new","ask","show","jobs"]},"maxResults":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}""") as JsonObject
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        check(allowed()) { "News plugin is disabled for this profile." }
        val action = text(arguments, "action") ?: "search"
        val source = text(arguments, "source") ?: "all"
        val query = text(arguments, "query").orEmpty()
        require(action in setOf("search", "top", "topic", "location", "trending", "hacker"))
        require(source in setOf("all", "google", "hacker") && query.length <= 250)
        require(action !in setOf("search", "location", "topic") || query.isNotBlank())
        val (defaultCountry, defaultLanguage) = defaults()
        val geo = text(arguments, "geo")
        val explicitCountry = text(arguments, "country")
        require(geo == null || explicitCountry == null || geo.equals(explicitCountry, ignoreCase = true)) { "country and geo must agree." }
        val country = (geo ?: explicitCountry ?: defaultCountry).uppercase(Locale.ROOT)
        val language = text(arguments, "language") ?: defaultLanguage
        require(country.matches(Regex("[A-Z]{2}")) && language.matches(Regex("[a-z]{2}(-[A-Z]{2})?")))
        val limit = (arguments["maxResults"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 10
        val jobs = buildList<Pair<String, suspend () -> NewsFeedResult>> {
            if (action == "trending") {
                add("trends" to { client.trends(country, limit, (arguments["related_queries"] as? JsonPrimitive)?.booleanOrNull ?: true) })
            } else {
                if (source != "hacker" && action != "hacker") add("google" to { client.google(action, query, country, language, limit) })
                if (action == "hacker" || source != "google" && action in setOf("search", "top")) {
                    add(
                        "hacker" to {
                            val articles = client.hacker(query, text(arguments, "type") ?: "top", limit)
                            NewsFeedResult(
                                articles,
                                listOf(
                                    buildJsonObject {
                                        put("provider", "hacker")
                                        put("status", if (articles.isEmpty()) "empty" else "ok")
                                        put("appCache", false)
                                        put("resultCount", articles.size)
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
        val feeds = coroutineScope {
            jobs.map { (provider, request) ->
                async {
                    try {
                        request()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        NewsFeedResult(
                            emptyList(),
                            listOf(
                                buildJsonObject {
                                    put("provider", provider)
                                    put("status", "error")
                                    put("errorCode", (failure as? NewsFeedException)?.code ?: "network_error")
                                    (failure as? NewsFeedException)?.status?.let { put("httpStatus", it) }
                                }
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        val articles = (0 until limit).flatMap { index -> feeds.mapNotNull { it.articles.getOrNull(index) } }
            .distinctBy { if (action == "trending") text(it, "title") else text(it, "url")?.takeIf(String::isNotBlank) ?: text(it, "title") }.take(limit)
        val diagnostics = feeds.flatMap { it.diagnostics }
        val hasFailures = diagnostics.any { text(it, "status") == "error" }
        check(allowed()) { "News plugin was disabled." }
        val payload = buildJsonObject {
            put("schema", "gptmobile.news.v1")
            put("retrievedAt", Instant.now().toString())
            put("country", country)
            put("geo", country)
            put("regionSource", if (geo != null || explicitCountry != null) "argument" else "plugin_settings")
            put("action", action)
            put("query", query)
            put("requestedSource", source)
            put("diagnostics", JsonArray(diagnostics))
            put("fallbackUsed", feeds.any { it.fallbackUsed })
            put("fallbackAttempted", diagnostics.any { text(it, "provider") == "bing" })
            put(
                "status",
                if (articles.isNotEmpty()) {
                    if (hasFailures || feeds.any { it.fallbackUsed }) "partial" else "ok"
                } else if (hasFailures) {
                    "unavailable"
                } else {
                    "no_results"
                }
            )
            put("appCache", false)
            put("language", language)
            put("articles", JsonArray(articles))
            put(
                "sources",
                JsonArray(
                    articles.flatMap { article ->
                        listOfNotNull(article["url"]) + (article["relatedNews"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("url") }
                    }.distinct()
                )
            )
            put("notice", if (action == "trending") "Search-interest signals from Google Trends; traffic is a reported approximate bucket, not an exact count. Related headlines provide context, not proof of causation. RSS does not supply related-query metrics. Fresh requests may still receive provider-cached data; inspect diagnostics and publication dates. Verify news before drawing conclusions." else "Publication dates and original publishers are preserved. Missing fields remain unknown. Bing fallback articles are labeled with feedProvider. Inspect diagnostics for provider errors and cache age; no app result cache is used.")
            if (articles.isEmpty()) {
                put("errorCode", if (hasFailures) "feed_unavailable" else "no_results")
                put("error", if (hasFailures) "News feeds could not be reached or returned an invalid feed. Inspect per-feed diagnostics; try later." else "The reachable feeds returned no matching articles. Try a different query or region.")
            }
        }
        AgentToolResult(callId, ToolResultContent.Json(payload), articles.isEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        AgentToolResult(callId, ToolResultContent.Text("Invalid News arguments. Check action, query, country/geo and language. country and geo must agree when both are supplied."), true)
    } catch (_: Exception) {
        AgentToolResult(callId, ToolResultContent.Text("News unavailable. Check the profile toggle. Public feeds require no key."), true)
    }

    private fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
}
