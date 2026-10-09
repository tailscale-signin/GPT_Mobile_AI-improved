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
        "News plugin: Google News search/top/topic/location, Google Trends, and Hacker News. No API key for public feeds. " +
            "Use source=all to combine Google News and Hacker News in one bounded call. Return dates and publishers; trends are interest signals, not verified news. Do not repeatedly search the same query.",
        Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["search","top","topic","location","trending","hacker"],"default":"search"},"query":{"type":"string","maxLength":250},"source":{"type":"string","enum":["all","google","hacker"],"default":"all"},"country":{"type":"string","pattern":"^[A-Za-z]{2}$"},"language":{"type":"string","pattern":"^[a-z]{2}(-[A-Z]{2})?$"},"type":{"type":"string","enum":["top","new","ask","show","jobs"]},"maxResults":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}""") as JsonObject
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
        val country = (text(arguments, "country") ?: defaultCountry).uppercase(Locale.ROOT)
        val language = text(arguments, "language") ?: defaultLanguage
        require(country.matches(Regex("[A-Z]{2}")) && language.matches(Regex("[a-z]{2}(-[A-Z]{2})?")))
        val limit = (arguments["maxResults"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 10
        val jobs = buildList<suspend () -> List<JsonObject>> {
            if (action == "trending") {
                add { client.trends(country, limit) }
            } else {
                if (source != "hacker" && action != "hacker") add { client.google(action, query, country, language, limit) }
                if (action == "hacker" || source != "google" && action in setOf("search", "top")) add { client.hacker(query, text(arguments, "type") ?: "top", limit) }
            }
        }
        val articles = coroutineScope {
            jobs.map { request ->
                async {
                    try {
                        request()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList<JsonObject>()
                    }
                }
            }.awaitAll().let { feeds ->
                // Interleave providers before limiting so one feed cannot crowd out the other.
                (0 until limit).flatMap { index -> feeds.mapNotNull { it.getOrNull(index) } }
            }
        }.distinctBy { if (action == "trending") text(it, "title") else text(it, "url")?.takeIf(String::isNotBlank) ?: text(it, "title") }.take(limit)
        check(allowed()) { "News plugin was disabled." }
        val payload = buildJsonObject {
            put("schema", "gptmobile.news.v1")
            put("retrievedAt", Instant.now().toString())
            put("country", country)
            put("language", language)
            put("articles", JsonArray(articles))
            put("sources", JsonArray(articles.mapNotNull { it["url"] }))
            put("notice", if (action == "trending") "Search-interest signals from Google Trends; verify news before drawing conclusions." else "Publication dates and original publishers are preserved. Missing fields remain unknown.")
            if (articles.isEmpty()) put("error", "News feeds returned no results or are temporarily unavailable. Broaden the query or try later; do not repeat this call in the same response.")
        }
        AgentToolResult(callId, ToolResultContent.Json(payload), articles.isEmpty())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AgentToolResult(callId, ToolResultContent.Text("News unavailable. Check the profile toggle, query, country and language. Public feeds require no key."), true)
    }

    private fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
}
