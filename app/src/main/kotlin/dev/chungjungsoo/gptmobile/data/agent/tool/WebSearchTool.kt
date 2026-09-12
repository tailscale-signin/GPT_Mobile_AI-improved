package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.model.WebSearchProvider
import dev.chungjungsoo.gptmobile.data.model.SettingPreferences
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WebSearchTool(
    private val client: OkHttpClient,
    private val json: Json,
    private val getSettings: () -> SettingPreferences
) : AgentTool {

    override val definition = ToolDefinition(
        name = "web_search",
        description = "Searches the web for current, factual, or time-sensitive information.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Search query terms.")
                    }
                )
                put(
                    "maxResults",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 10)
                        put("description", "Maximum number of search results to return.")
                    }
                )
                put(
                    "includeDomains",
                    buildJsonObject {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put("description", "Domains to restrict results to.")
                    }
                )
                put(
                    "excludeDomains",
                    buildJsonObject {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "string")
                        }
                        put("description", "Domains to exclude from results.")
                    }
                )
                put(
                    "recencyDays",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Filter results to those published within the last N days.")
                    }
                )
            }
            putJsonArray("required") {
                add("query")
            }
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val validation = validateArguments(arguments)
        if (validation.errors.isNotEmpty()) {
            return@withContext ToolResult(
                callId = callId,
                content = ToolResultContent.Text("Web search failed: ${validation.errors.joinToString("; ")}"),
                isError = true
            )
        }

        val settings = getSettings()
        val provider = settings.webSearchProvider

        val query = validation.query
        val maxResults = validation.maxResults ?: DEFAULT_MAX_RESULTS
        val includeDomains = validation.includeDomains
        val excludeDomains = validation.excludeDomains
        val recencyDays = validation.recencyDays

        try {
            val rawResults = when (provider) {
                WebSearchProvider.DUCKDUCKGO -> queryDuckDuckGo(query, maxResults)
                WebSearchProvider.FIRECRAWL -> queryFirecrawl(settings, query, maxResults)
                WebSearchProvider.PERPLEXITY -> queryPerplexity(settings, query, maxResults)
                WebSearchProvider.EXA -> queryExa(settings, query, maxResults)
            }

            val filtered = rawResults
                .filter { item ->
                    val host = item.host
                    val includeMatch = includeDomains.isEmpty() || includeDomains.any { host.endsWith(it.lowercase()) }
                    val excludeMatch = excludeDomains.any { host.endsWith(it.lowercase()) }
                    includeMatch && !excludeMatch
                }
                .take(maxResults)

            val payload = buildJsonObject {
                put("provider", provider.name.lowercase(Locale.ROOT))
                put("query", query)
                put("resultCount", filtered.size)
                put(
                    "results",
                    buildJsonArray {
                        filtered.forEach { item ->
                            addJsonObject {
                                put("title", item.title)
                                put("url", item.url)
                                put("snippet", item.snippet)
                                put("publishedDate", item.publishedDate)
                            }
                        }
                    }
                )
            }

            ToolResult(
                callId = callId,
                content = ToolResultContent.Text(payload.toString()),
                isError = false
            )
        } catch (e: Exception) {
            safeLogWarn("Web search execution failed: ${e.message}")
            ToolResult(
                callId = callId,
                content = ToolResultContent.Text("Web search failed: ${e.message ?: "Unknown error"}"),
                isError = true
            )
        }
    }

    private fun queryDuckDuckGo(query: String, maxResults: Int): List<SearchResultItem> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val endpoints = listOf(
            "https://html.duckduckgo.com/html/?q=$encodedQuery",
            "https://duckduckgo.com/html/?q=$encodedQuery"
        )

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        safeLogWarn("DuckDuckGo endpoint $url returned HTTP ${response.code}")
                        return@use
                    }
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) {
                        safeLogWarn("DuckDuckGo endpoint $url returned empty body")
                        return@use
                    }
                    val parsed = parseDuckDuckGoHtml(html, maxResults)
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                safeLogWarn("DuckDuckGo endpoint $url failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()

        // Split or extract individual result blocks:
        // DDG uses .web-result, .result, .nrn-react-div, or result__body
        val resultBlockRegex = Regex("""(?:class="[^"]*(?:web-result|result\b|result__body)[^"]*"|data-testid="result")[^>]*>(.*?)(?=(?:class="[^"]*(?:web-result|result\b|result__body)[^"]*"|data-testid="result")|</body>|</html>|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val blocks = resultBlockRegex.findAll(html).map { it.groupValues[1] }.toList()

        val candidateBlocks = if (blocks.isNotEmpty()) blocks else html.split(Regex("""<div[^>]+class="[^"]*result[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)).drop(1)

        val titleRegex = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val fallbackTitleRegex = Regex("""<a[^>]+href="([^"]+)"[^>]+data-testid="result-title-a"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val h2TitleRegex = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val snippetRegex = Regex("""<(?:a|div|span)[^>]+class="[^"]*(?:result__snippet|snippet)[^"]*"[^>]*>(.*?)</(?:a|div|span)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val fallbackSnippetRegex = Regex("""<(?:div|span|p)[^>]+data-testid="result-snippet"[^>]*>(.*?)</(?:div|span|p)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (block in candidateBlocks) {
            val titleMatch = titleRegex.find(block) ?: fallbackTitleRegex.find(block) ?: h2TitleRegex.find(block)
            val rawUrl = titleMatch?.groupValues?.get(1).orEmpty()
            val rawTitle = titleMatch?.groupValues?.get(2).orEmpty()

            val url = extractActualUrl(rawUrl)
            val title = cleanHtml(rawTitle)

            val snippetMatch = snippetRegex.find(block) ?: fallbackSnippetRegex.find(block)
            val rawSnippet = snippetMatch?.groupValues?.get(1).orEmpty()
            val snippet = cleanHtml(rawSnippet)

            if (url.isNotBlank() && title.isNotBlank()) {
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                results += SearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedDate = null,
                    host = host
                )
            }

            if (results.size >= maxResults) {
                break
            }
        }

        return results
    }

    private fun extractActualUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        val resolvedUrl = when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") && !rawUrl.startsWith("/l/?") && !rawUrl.startsWith("/html/?") -> "https://duckduckgo.com$rawUrl"
            else -> rawUrl
        }
        val uri = runCatching { URI(resolvedUrl) }.getOrNull() ?: return resolvedUrl
        val queryParams = uri.rawQuery?.split("&").orEmpty()
        for (param in queryParams) {
            val parts = param.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "uddg") {
                return runCatching { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) }.getOrDefault(resolvedUrl)
            }
        }
        return resolvedUrl
    }

    private fun cleanHtml(text: String): String {
        val withoutTags = text.replace(Regex("<[^>]+>"), " ")
        val decoded = withoutTags
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFKC)
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun queryFirecrawl(settings: SettingPreferences, query: String, maxResults: Int): List<SearchResultItem> {
        val apiKey = settings.firecrawlApiKey
        require(apiKey.isNotBlank()) { "Firecrawl API key is required" }

        val requestBody = buildJsonObject {
            put("query", query)
            put("limit", maxResults)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(resolveEndpoint(settings.firecrawlBaseUrl, "https://api.firecrawl.dev/v1/search"))
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Firecrawl request failed with HTTP ${response.code}: $body")
            }

            val root = json.parseToJsonElement(body).jsonObject
            val dataArray = root["data"]?.jsonArray
                ?: root["results"]?.jsonArray
                ?: buildJsonArray { }

            return dataArray.mapNotNull { item ->
                val obj = item.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: url
                val snippet = obj["description"]?.jsonPrimitive?.content
                    ?: obj["content"]?.jsonPrimitive?.content
                    ?: ""
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                SearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedDate = null,
                    host = host
                )
            }
        }
    }

    private fun queryPerplexity(settings: SettingPreferences, query: String, maxResults: Int): List<SearchResultItem> {
        val apiKey = settings.perplexityApiKey
        require(apiKey.isNotBlank()) { "Perplexity API key is required" }

        val requestBody = buildJsonObject {
            put("model", "sonar")
            put(
                "messages",
                buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put("content", query)
                    }
                }
            )
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(resolveEndpoint(settings.perplexityBaseUrl, "https://api.perplexity.ai/chat/completions"))
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Perplexity request failed with HTTP ${response.code}: $body")
            }

            val root = json.parseToJsonElement(body).jsonObject
            val citations = root["citations"]?.jsonArray.orEmpty()
            val answer = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                .orEmpty()

            return citations.mapNotNull { citation ->
                val url = citation.jsonPrimitive.content
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                SearchResultItem(
                    title = host.ifBlank { url },
                    url = url,
                    snippet = answer,
                    publishedDate = null,
                    host = host
                )
            }.take(maxResults)
        }
    }

    private fun queryExa(settings: SettingPreferences, query: String, maxResults: Int): List<SearchResultItem> {
        val apiKey = settings.exaApiKey
        require(apiKey.isNotBlank()) { "Exa API key is required" }

        val requestBody = buildJsonObject {
            put("query", query)
            put("numResults", maxResults)
            putJsonObject("contents") {
                put("highlights", true)
            }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(resolveEndpoint(settings.exaBaseUrl, "https://api.exa.ai/search"))
            .addHeader("x-api-key", apiKey)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Exa request failed with HTTP ${response.code}: $body")
            }

            val root = json.parseToJsonElement(body).jsonObject
            val resultsArray = root["results"]?.jsonArray.orEmpty()

            return resultsArray.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: url
                val highlights = obj["highlights"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                val snippet = highlights?.joinToString(" ")
                    ?: obj["text"]?.jsonPrimitive?.content
                    ?: ""
                val publishedDate = obj["publishedDate"]?.jsonPrimitive?.content
                val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
                SearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet,
                    publishedDate = publishedDate,
                    host = host
                )
            }
        }
    }

    private fun resolveEndpoint(custom: String, defaultUrl: String): String {
        return if (custom.isNotBlank()) custom else defaultUrl
    }

    private fun validateArguments(arguments: JsonObject): ValidationResult {
        val errors = mutableListOf<String>()

        val queryElement = arguments["query"]
        val query = queryElement?.jsonPrimitive?.content?.trim().orEmpty()
        if (queryElement == null || query.isBlank()) {
            errors += "query is required and cannot be blank"
        }

        val maxResultsElement = arguments["maxResults"]
        val maxResults = maxResultsElement?.jsonPrimitive?.intOrNull
        if (maxResultsElement != null && maxResults == null) {
            errors += "maxResults must be an integer"
        } else if (maxResults != null && maxResults !in 1..10) {
            errors += "maxResults must be between 1 and 10"
        }

        val includeDomains = parseStringList(arguments["includeDomains"], "includeDomains", errors)
        val excludeDomains = parseStringList(arguments["excludeDomains"], "excludeDomains", errors)

        val recencyDaysElement = arguments["recencyDays"]
        val recencyDays = recencyDaysElement?.jsonPrimitive?.intOrNull
        if (recencyDaysElement != null && recencyDays == null) {
            errors += "recencyDays must be an integer"
        } else if (recencyDays != null && recencyDays < 1) {
            errors += "recencyDays must be greater than or equal to 1"
        }

        return ValidationResult(
            query = query,
            maxResults = maxResults,
            includeDomains = includeDomains,
            excludeDomains = excludeDomains,
            recencyDays = recencyDays,
            errors = errors
        )
    }

    private fun parseStringList(element: JsonElement?, fieldName: String, errors: MutableList<String>): List<String> {
        if (element == null) return emptyList()
        return try {
            element.jsonArray.mapNotNull { item ->
                item.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            errors += "$fieldName must be an array of strings"
            emptyList()
        }
    }

    private fun safeLogWarn(message: String) {
        runCatching {
            android.util.Log.w("WebSearchTool", message)
        }
    }

    private data class ValidationResult(
        val query: String,
        val maxResults: Int?,
        val includeDomains: List<String>,
        val excludeDomains: List<String>,
        val recencyDays: Int?,
        val errors: List<String>
    )

    private data class SearchResultItem(
        val title: String,
        val url: String,
        val snippet: String,
        val publishedDate: String?,
        val host: String
    )

    private companion object {
        const val DEFAULT_MAX_RESULTS = 5
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
