package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Built-in agent tool for querying the GitHub REST API.
 * Supports searching repositories, searching issues/pull requests, fetching file contents,
 * and reading issue/PR details.
 */
class GitHubTool(
    private val apiToken: String = "",
    private val httpClient: HttpClient = defaultHttpClient
) : AgentTool {

    companion object {
        private const val BASE_URL = "https://api.github.com"
        private const val MAX_OUTPUT_CHARS = 32_000

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val defaultHttpClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                engine {
                    config {
                        retryOnConnectionFailure(true)
                    }
                }
            }
        }
    }

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = BuiltInAgentTool.GITHUB,
        description = "Query GitHub to search repositories, search issues/pull requests, read repository file contents, or get issue details.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Action to perform: 'search_repositories', 'search_issues', 'get_file_contents', or 'get_issue'."
                            )
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("search_repositories"))
                                    add(JsonPrimitive("search_issues"))
                                    add(JsonPrimitive("get_file_contents"))
                                    add(JsonPrimitive("get_issue"))
                                }
                            )
                        }
                    )
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Search query when action is 'search_repositories' or 'search_issues'.")
                        }
                    )
                    put(
                        "owner",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Repository owner (user or organization). Required for get_file_contents and get_issue.")
                        }
                    )
                    put(
                        "repo",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Repository name. Required for get_file_contents and get_issue.")
                        }
                    )
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "File path within repository. Required for get_file_contents.")
                        }
                    )
                    put(
                        "ref",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Branch name, commit SHA, or tag for get_file_contents. Defaults to default branch.")
                        }
                    )
                    put(
                        "issue_number",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Issue or pull request number. Required for get_issue.")
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("action"))
                }
            )
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content?.trim()
            ?: return errorResult(callId, "Missing required parameter: 'action'.")

        return try {
            when (action) {
                "search_repositories" -> handleSearchRepositories(callId, arguments)
                "search_issues" -> handleSearchIssues(callId, arguments)
                "get_file_contents" -> handleGetFileContents(callId, arguments)
                "get_issue" -> handleGetIssue(callId, arguments)
                else -> errorResult(callId, "Unknown action: '$action'. Supported actions: search_repositories, search_issues, get_file_contents, get_issue.")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            errorResult(callId, "GitHub request failed: ${throwable.localizedMessage ?: throwable.message ?: "Unknown error"}")
        }
    }

    private suspend fun handleSearchRepositories(callId: String, arguments: JsonObject): AgentToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
        if (query.isNullOrEmpty()) {
            return errorResult(callId, "Parameter 'query' is required for action 'search_repositories'.")
        }
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/search/repositories?q=$encodedQuery&per_page=10"
        val response = getGitHubApi(url)
        val text = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }

        val json = jsonParser.parseToJsonElement(text).jsonObject
        val items = json["items"]?.jsonArray ?: JsonArray(emptyList())
        val summary = buildJsonObject {
            put("total_count", json["total_count"] ?: JsonPrimitive(0))
            put(
                "items",
                buildJsonArray {
                    items.take(10).forEach { item ->
                        val obj = item.jsonObject
                        add(
                            buildJsonObject {
                                put("full_name", obj["full_name"] ?: JsonPrimitive(""))
                                put("description", obj["description"] ?: JsonPrimitive(""))
                                put("stargazers_count", obj["stargazers_count"] ?: JsonPrimitive(0))
                                put("language", obj["language"] ?: JsonPrimitive(""))
                                put("html_url", obj["html_url"] ?: JsonPrimitive(""))
                            }
                        )
                    }
                }
            )
        }
        return successResult(callId, summary.toString())
    }

    private suspend fun handleSearchIssues(callId: String, arguments: JsonObject): AgentToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
        if (query.isNullOrEmpty()) {
            return errorResult(callId, "Parameter 'query' is required for action 'search_issues'.")
        }
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/search/issues?q=$encodedQuery&per_page=10"
        val response = getGitHubApi(url)
        val text = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }

        val json = jsonParser.parseToJsonElement(text).jsonObject
        val items = json["items"]?.jsonArray ?: JsonArray(emptyList())
        val summary = buildJsonObject {
            put("total_count", json["total_count"] ?: JsonPrimitive(0))
            put(
                "items",
                buildJsonArray {
                    items.take(10).forEach { item ->
                        val obj = item.jsonObject
                        add(
                            buildJsonObject {
                                put("number", obj["number"] ?: JsonPrimitive(0))
                                put("title", obj["title"] ?: JsonPrimitive(""))
                                put("state", obj["state"] ?: JsonPrimitive(""))
                                put("html_url", obj["html_url"] ?: JsonPrimitive(""))
                                put("created_at", obj["created_at"] ?: JsonPrimitive(""))
                            }
                        )
                    }
                }
            )
        }
        return successResult(callId, summary.toString())
    }

    private suspend fun handleGetFileContents(callId: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim()
        val path = arguments["path"]?.jsonPrimitive?.content?.trim()
        val ref = arguments["ref"]?.jsonPrimitive?.content?.trim()

        if (owner.isNullOrEmpty() || repo.isNullOrEmpty() || path.isNullOrEmpty()) {
            return errorResult(callId, "Parameters 'owner', 'repo', and 'path' are required for action 'get_file_contents'.")
        }

        val cleanPath = path.removePrefix("/")
        val refQuery = if (!ref.isNullOrEmpty()) "?ref=${URLEncoder.encode(ref, StandardCharsets.UTF_8.name())}" else ""
        val url = "$BASE_URL/repos/$owner/$repo/contents/$cleanPath$refQuery"
        val response = getGitHubApi(url)
        val text = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }

        val json = jsonParser.parseToJsonElement(text).jsonObject
        val encoding = json["encoding"]?.jsonPrimitive?.content
        val contentBase64 = json["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""

        val decodedContent = if (encoding == "base64" && contentBase64.isNotEmpty()) {
            try {
                String(Base64.getDecoder().decode(contentBase64), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                "[Failed to decode base64 content: ${e.message}]"
            }
        } else {
            text
        }

        val truncated = truncate(decodedContent, MAX_OUTPUT_CHARS)
        return successResult(callId, truncated)
    }

    private suspend fun handleGetIssue(callId: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim()
        val issueNumber = arguments["issue_number"]?.jsonPrimitive?.intOrNull

        if (owner.isNullOrEmpty() || repo.isNullOrEmpty() || issueNumber == null) {
            return errorResult(callId, "Parameters 'owner', 'repo', and 'issue_number' are required for action 'get_issue'.")
        }

        val url = "$BASE_URL/repos/$owner/$repo/issues/$issueNumber"
        val response = getGitHubApi(url)
        val text = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }

        val json = jsonParser.parseToJsonElement(text).jsonObject
        val summary = buildJsonObject {
            put("number", json["number"] ?: JsonPrimitive(issueNumber))
            put("title", json["title"] ?: JsonPrimitive(""))
            put("state", json["state"] ?: JsonPrimitive(""))
            put("user", json["user"]?.jsonObject?.get("login") ?: JsonPrimitive(""))
            put("created_at", json["created_at"] ?: JsonPrimitive(""))
            put("body", JsonPrimitive(truncate(json["body"]?.jsonPrimitive?.content ?: "", 4000)))
            put("html_url", json["html_url"] ?: JsonPrimitive(""))
        }
        return successResult(callId, summary.toString())
    }

    private suspend fun getGitHubApi(url: String): HttpResponse {
        return httpClient.get(url) {
            header(HttpHeaders.Accept, "application/vnd.github.v3+json")
            header(HttpHeaders.UserAgent, "GPT-Mobile-App")
            if (apiToken.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
            }
        }
    }

    private fun truncate(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text else text.take(maxLength) + "\n...[truncated]"
    }

    private fun successResult(callId: String, text: String): AgentToolResult =
        AgentToolResult(
            callId = callId,
            content = ToolResultContent.Text(text),
            isError = false
        )

    private fun errorResult(callId: String, errorMessage: String): AgentToolResult =
        AgentToolResult(
            callId = callId,
            content = ToolResultContent.Text(errorMessage),
            isError = true
        )
}
