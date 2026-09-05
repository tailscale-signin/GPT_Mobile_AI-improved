package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class WebSearchProvider {
    FIRECRAWL,
    PERPLEXITY,
    EXA,
    AUTO
}

data class WebSearchProviderConfig(
    val provider: WebSearchProvider,
    val bearerToken: String,
    val endpointUrl: String
)

class WebSearchTool(
    private val config: WebSearchProviderConfig,
    private val networkClient: NetworkClient,
    private val clock: Clock = Clock.systemUTC()
) : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = "web_search",
        description = "Search the web and return normalized results with title, url, snippet, and optional publishedDate.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                    put(
                        "maxResults",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 100)
                        }
                    )
                    put("includeDomains", domainArraySchema())
                    put("excludeDomains", domainArraySchema())
                    put(
                        "recencyDays",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                        }
                    )
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val request = parseRequest(arguments) ?: return error(callId, "Invalid web search request: ${validationErrors(arguments).joinToString("; ")}.")

        return if (config.provider == WebSearchProvider.AUTO) {
            executeAutoSearch(callId, request)
        } else {
            executeConfiguredProvider(callId, request)
        }
    }

    private suspend fun executeConfiguredProvider(callId: String, request: WebSearchRequest): AgentToolResult {
        return try {
            val response = networkClient().post(config.endpointUrl) {
                when (config.provider) {
                    WebSearchProvider.EXA -> header("x-api-key", config.bearerToken)
                    WebSearchProvider.FIRECRAWL, WebSearchProvider.PERPLEXITY -> bearerAuth(config.bearerToken)
                    WebSearchProvider.AUTO -> Unit
                }
                setBody(payload(request))
            }
            if (response.status.value !in 200..299) {
                return error(callId, "Web search failed: HTTP ${response.status.value}.")
            }
            val content = runCatching { normalized(config.provider, response.bodyAsText()) }.getOrElse { exception ->
                return if (exception is MissingRequiredResultFieldException) {
                    error(callId, "Web search failed: missing required result fields.")
                } else {
                    error(callId, "Web search failed: malformed or unsupported provider response.")
                }
            }
            AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(content),
                isError = false
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error(callId, "Web search failed: malformed or unsupported provider response.")
        }
    }

    private suspend fun executeAutoSearch(callId: String, request: WebSearchRequest): AgentToolResult {
        // Stage 1: Try Local Termux MCPSearch Daemon if active (e.g. http://127.0.0.1:8000/search)
        val termuxResult = runCatching { tryTermuxMcpSearch(request) }.getOrNull()
        if (termuxResult != null && termuxResult.isNotEmpty()) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(buildJsonObject { put("results", JsonArray(termuxResult)) }),
                isError = false
            )
        }

        // Stage 2: Fall back to DuckDuckGo Free Search (No API Key Required)
        return try {
            val ddgResults = queryDuckDuckGo(request)
            AgentToolResult(
                callId = callId,
                content = ToolResultContent.Json(buildJsonObject { put("results", JsonArray(ddgResults)) }),
                isError = false
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            error(callId, "Web search failed: could not retrieve search results.")
        }
    }

    private suspend fun tryTermuxMcpSearch(request: WebSearchRequest): List<JsonObject>? {
        val endpoint = config.endpointUrl.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8000/search"
        val response = networkClient().get(endpoint) {
            parameter("query", request.query)
            parameter("limit", request.maxResults)
        }
        if (response.status.value !in 200..299) return null
        val bodyText = response.bodyAsText()
        val root = NetworkClient.json.parseToJsonElement(bodyText).jsonObject
        val rawResults = root["results"]?.jsonArray ?: return null
        return rawResults.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = obj.string("title") ?: return@mapNotNull null
            val url = obj.string("url") ?: return@mapNotNull null
            val snippet = obj.string("snippet") ?: obj.string("description") ?: ""
            buildJsonObject {
                put("title", title)
                put("url", url)
                put("snippet", snippet)
                obj.string("publishedDate")?.let { put("publishedDate", it) }
            }
        }.take(request.maxResults)
    }

    private suspend fun queryDuckDuckGo(request: WebSearchRequest): List<JsonObject> {
        val encodedQuery = URLEncoder.encode(request.query, StandardCharsets.UTF_8.name())
        val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
        val response = networkClient().get(url) {
            header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/114.0 Firefox/114.0")
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("DuckDuckGo HTML search HTTP ${response.status.value}")
        }
        val html = response.bodyAsText()
        val parsed = parseDuckDuckGoHtml(html)
        var filtered = parsed
        if (request.includeDomains.isNotEmpty()) {
            filtered = filtered.filter { result ->
                val resultUrl = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                request.includeDomains.any { domain -> resultUrl.contains(domain, ignoreCase = true) }
            }
        }
        if (request.excludeDomains.isNotEmpty()) {
            filtered = filtered.filter { result ->
                val resultUrl = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                request.excludeDomains.none { domain -> resultUrl.contains(domain, ignoreCase = true) }
            }
        }
        return filtered.take(request.maxResults)
    }

    private fun parseDuckDuckGoHtml(html: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        // Match DuckDuckGo result blocks: class="result__body" or class="result results_links"
        val linkRegex = Regex("""<a class="result__url"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>|<a class="result__snippet[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("""<a class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val snippetRegex = Regex("""<a class="result__snippet[^"]*"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        val titleMatches = titleRegex.findAll(html).toList()
        val snippetMatches = snippetRegex.findAll(html).toList()

        for (i in titleMatches.indices) {
            val titleMatch = titleMatches[i]
            val rawHref = titleMatch.groupValues[1]
            val rawTitle = cleanHtml(titleMatch.groupValues[2])
            val actualUrl = extractActualUrl(rawHref)
            val snippet = if (i < snippetMatches.size) {
                cleanHtml(snippetMatches[i].groupValues[1])
            } else {
                ""
            }

            if (actualUrl.isNotBlank() && rawTitle.isNotBlank()) {
                results += buildJsonObject {
                    put("title", rawTitle)
                    put("url", actualUrl)
                    put("snippet", snippet)
                }
            }
        }
        return results
    }

    private fun extractActualUrl(href: String): String {
        // DuckDuckGo result URLs are formatted as /l/?uddg=https%3A%2F%2Fexample.com...
        if (href.contains("uddg=")) {
            val uddg = href.substringAfter("uddg=").substringBefore("&")
            return runCatching { java.net.URLDecoder.decode(uddg, StandardCharsets.UTF_8.name()) }.getOrDefault(href)
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseRequest(arguments: JsonObject): WebSearchRequest? {
        if (validationErrors(arguments).isNotEmpty()) return null
        return WebSearchRequest(
            query = arguments["query"]!!.jsonPrimitive.content.trim(),
            maxResults = intArgument(arguments, "maxResults") ?: 10,
            includeDomains = domains(arguments["includeDomains"]),
            excludeDomains = domains(arguments["excludeDomains"]),
            recencyDays = intArgument(arguments, "recencyDays")
        )
    }

    private fun validationErrors(arguments: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        val query = stringArgument(arguments, "query")?.trim().orEmpty()
        val maxResults = intArgument(arguments, "maxResults")
        val recencyDays = intArgument(arguments, "recencyDays")
        val includeDomains = domains(arguments["includeDomains"])
        val excludeDomains = domains(arguments["excludeDomains"])

        if (query.isBlank()) errors += "query is required"
        if (arguments["maxResults"] != null && maxResults == null) errors += "maxResults must be an integer"
        if (maxResults != null && maxResults !in 1..100) errors += "maxResults must be between 1 and 100"
        if (arguments["recencyDays"] != null && recencyDays == null) errors += "recencyDays must be an integer"
        if (recencyDays != null && recencyDays < 0) errors += "recencyDays must be nonnegative"
        if (arguments["includeDomains"] != null && arguments["includeDomains"] !is JsonArray) errors += "includeDomains must be an array"
        if (arguments["excludeDomains"] != null && arguments["excludeDomains"] !is JsonArray) errors += "excludeDomains must be an array"
        if (!domainElementsAreStrings(arguments["includeDomains"]) || !domainElementsAreStrings(arguments["excludeDomains"])) errors += "domains must be strings"
        if (includeDomains.any { !it.isValidDomain() } || excludeDomains.any { !it.isValidDomain() }) errors += "domains must be host names"
        if (includeDomains.isNotEmpty() && excludeDomains.isNotEmpty()) errors += "includeDomains and excludeDomains cannot both be set"
        return errors
    }

    private fun payload(request: WebSearchRequest): JsonObject = when (config.provider) {
        WebSearchProvider.FIRECRAWL -> buildJsonObject {
            put("query", request.query)
            put("limit", request.maxResults)
            if (request.includeDomains.isNotEmpty()) put("includeDomains", request.includeDomains.toJsonArray())
            if (request.excludeDomains.isNotEmpty()) put("excludeDomains", request.excludeDomains.toJsonArray())
            request.recencyDays?.let { put("tbs", firecrawlTbs(it)) }
        }

        WebSearchProvider.PERPLEXITY -> buildJsonObject {
            put("query", request.query)
            put("max_results", request.maxResults)
            val domainFilter = request.includeDomains.ifEmpty { request.excludeDomains.map { "-$it" } }
            if (domainFilter.isNotEmpty()) put("search_domain_filter", domainFilter.toJsonArray())
            request.recencyDays?.let { put("search_after_date_filter", usDate(today().minusDays(it.toLong()))) }
        }

        WebSearchProvider.EXA -> buildJsonObject {
            put("query", request.query)
            put("numResults", request.maxResults)
            if (request.includeDomains.isNotEmpty()) put("includeDomains", request.includeDomains.toJsonArray())
            if (request.excludeDomains.isNotEmpty()) put("excludeDomains", request.excludeDomains.toJsonArray())
            request.recencyDays?.let { put("startPublishedDate", todayInstantMinusDays(it)) }
            put("contents", buildJsonObject { put("highlights", true) })
        }

        WebSearchProvider.AUTO -> buildJsonObject {
            put("query", request.query)
            put("limit", request.maxResults)
        }
    }

    private fun normalized(provider: WebSearchProvider, body: String): JsonObject {
        val root = NetworkClient.json.parseToJsonElement(body).jsonObject
        val rawResults = when (provider) {
            WebSearchProvider.FIRECRAWL -> root["data"]?.jsonObject?.get("web")?.jsonArray
            WebSearchProvider.PERPLEXITY -> root["results"]?.jsonArray
            WebSearchProvider.EXA -> root["results"]?.jsonArray
            WebSearchProvider.AUTO -> root["results"]?.jsonArray
        } ?: throw IllegalArgumentException("missing results")
        val results = rawResults.map { element ->
            val value = element.jsonObject
            val title = value.string("title")
            val url = value.string("url")
            val snippet = value.string("snippet") ?: value.string("description") ?: value.highlights() ?: value.string("text") ?: value.string("summary")
            if (title == null || url == null || snippet == null) throw MissingRequiredResultFieldException()
            buildJsonObject {
                put("title", title)
                put("url", url)
                put("snippet", snippet)
                (value.string("publishedDate") ?: value.string("date"))?.let { put("publishedDate", it) }
            }
        }
        return buildJsonObject { put("results", JsonArray(results)) }
    }

    private fun domains(element: JsonElement?): List<String> = element
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { stringValue(it)?.trim()?.lowercase(Locale.US) }
        .orEmpty()

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun firecrawlTbs(days: Int): String {
        val start = today().minusDays(days.toLong())
        val end = today()
        return "cdr:1,cd_min:${usDate(start)},cd_max:${usDate(end)}"
    }

    private fun todayInstantMinusDays(days: Int): String = clock.instant().minusSeconds(days * 24L * 60L * 60L).toString()

    private fun usDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US))

    private fun error(callId: String, message: String): AgentToolResult = AgentToolResult(
        callId = callId,
        content = ToolResultContent.Text(message.take(240)),
        isError = true
    )

    private fun intArgument(arguments: JsonObject, name: String): Int? = runCatching {
        arguments[name]?.jsonPrimitive?.takeUnless { it.toString().startsWith("\"") }?.intOrNull
    }.getOrNull()

    private fun stringArgument(arguments: JsonObject, name: String): String? = runCatching {
        arguments[name]?.let { stringValue(it) }
    }.getOrNull()
}

private data class WebSearchRequest(
    val query: String,
    val maxResults: Int,
    val includeDomains: List<String>,
    val excludeDomains: List<String>,
    val recencyDays: Int?
)

private class MissingRequiredResultFieldException : Exception()

private fun domainArraySchema(): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
}

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    this@toJsonArray.forEach { add(JsonPrimitive(it)) }
}

private fun String.isValidDomain(): Boolean = isNotBlank() && !contains("/") && !contains(":") && none { it.isWhitespace() }

private fun domainElementsAreStrings(element: JsonElement?): Boolean = element !is JsonArray ||
    element.all { value ->
        stringValue(value) != null
    }

private fun JsonObject.string(name: String): String? = this[name]?.let { stringValue(it) }?.takeIf { it.isNotBlank() }

private fun JsonObject.highlights(): String? = this["highlights"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { stringValue(it)?.takeIf { highlight -> highlight.isNotBlank() } }
    ?.joinToString("\n")
    ?.takeIf { it.isNotBlank() }

private fun stringValue(element: JsonElement): String? {
    val primitive = runCatching { element.jsonPrimitive }.getOrNull() ?: return null
    return primitive.contentOrNull?.takeIf { primitive.toString().startsWith("\"") }
}
