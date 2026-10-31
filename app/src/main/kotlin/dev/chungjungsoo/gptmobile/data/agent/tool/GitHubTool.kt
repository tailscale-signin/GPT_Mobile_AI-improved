package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.normalizeGitHubWorkflowSelector
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
    private val httpClient: HttpClient = defaultHttpClient,
    private val modelToolName: String = BuiltInAgentTool.GITHUB,
    private val accountName: String? = null,
    private val repositoryContext: GitHubRepositoryContext? = null,
    private val featureSettings: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(),
    private val connectionUid: String = "",
    private val conversationId: String = "",
    private val taskId: String = "",
    private val persistentState: Boolean = true,
    private val stateStore: dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore? = null,
    private val engineeringPrepare: (suspend (String, JsonObject) -> JsonObject)? = null,
    private val repositoryScope: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null,
    private val credentialAdmissionFailed: Boolean = false,
    private val watchChecks: (suspend (JsonObject) -> JsonObject)? = null
) : AgentTool {

    private val authenticationRejected = if (httpClient === defaultHttpClient) dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches.authenticationForCredential(apiToken) else java.util.concurrent.atomic.AtomicBoolean()
    private val rateLimits = if (httpClient === defaultHttpClient) dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches.rateLimitsForCredential(apiToken) else dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitManager()
    internal val hasCredential get() = apiToken.isNotBlank()
    internal val hasRepositoryContext get() = repositoryContext != null
    private val scheduler = dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches.schedulerForCredential(apiToken)
    private val responseCache = if (httpClient === defaultHttpClient) dev.chungjungsoo.gptmobile.data.github.GitHubSessionCaches.forCredential(apiToken) else dev.chungjungsoo.gptmobile.data.github.GitHubResponseCache()
    private val workspaceClient by lazy {
        GitHubWorkspaceClient(
            apiToken, httpClient, rateLimits = rateLimits, authenticationRejected = authenticationRejected,
            responseCache = responseCache,
            conditionalReads = featureSettings.githubConditionalReads, blobCache = featureSettings.githubBlobCache,
            freshnessSeconds = featureSettings.pluginExecution[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB]?.githubCacheSeconds ?: 15,
            connectionUid = connectionUid, conversationId = conversationId, taskId = taskId, persistentState = persistentState, stateStore = stateStore, scheduler = scheduler, engineeringPrepare = engineeringPrepare
        )
    }
    internal fun operations(): List<GitHubOperationTool> {
        val suffix = modelToolName.removePrefix("github").takeIf { it.startsWith("__") }.orEmpty()
        val scope = GitHubResultViews.scope(apiToken, connectionUid, conversationId, taskId)
        return GitHubOperationTool.names.map { GitHubOperationTool(it, this, scope, it + suffix) }
    }

    companion object {
        private const val BASE_URL = "https://api.github.com"
        private const val MAX_OUTPUT_CHARS = 32_000
        private val MUTATING_ACTIONS = GitHubWorkspaceClient.writeActions + setOf(
            "create_branch",
            "update_file",
            "create_pull_request",
            "create_issue_comment",
            "dispatch_workflow",
            "rerun_workflow",
            "rerun_failed_jobs",
            "cancel_workflow"
        )

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val defaultHttpClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                install(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsHttpPlugin)
                followRedirects = false
                install(io.ktor.client.plugins.HttpTimeout) {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
                engine {
                    config {
                        retryOnConnectionFailure(false)
                    }
                }
            }
        }
    }

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = modelToolName,
        description = "Work with GitHub repositories through one native integration. Prefer compact high-level actions: repo_status for an overview, repo_docs to discover llms.txt, README and docs at a pinned commit, repo_map before code reads, find_symbol/find_references/find_tests for discovery, pr_context with include_health=true for current merge rules and required-check inspection, changed_since for compact diffs, release_status before releases, publish_release for end-to-end publication, and plan_change before multi-step writes. publish_release prefers an active repository release workflow and falls back to the GitHub Releases API when appropriate. Use write_capabilities to inspect repository write access when a write is denied. HTTP 403 alone does not prove a read-only token. Do not tell the user to use local gh or the GitHub Web UI while this action is available. Never fall back to shell, terminal, git CLI, gh CLI, or POSIX execution for a GitHub task while this integration is available. Use read_code line ranges instead of full files. Optional GitMCP tools provide public documentation/code search only; their results are not evidence of the selected branch. Verify relevant paths with repo_docs and read_code at its commit_sha. Private repositories, issues, PRs and writes stay in this native integration." + (accountName?.let { " Authenticated connection: $it. Use this tool for repositories available to this account." } ?: " Public read access; configure a GitHub API connection for private repositories and writes.") + (repositoryContext?.let { " Selected repository: ${it.fullName}, branch: ${it.ref}. Omitted owner/repo/ref use this selection. Read code in line ranges and include source paths. Read get_branch_head before commit_files; supply expected_head_sha. Commit only to a working branch and create a draft PR for review." } ?: ""),
        modelVisible = false,
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
                                "GitHub action. Use browse_files for directory navigation, read_code for line ranges with metadata, get_branch_head then commit_files for atomic changes on a working branch. Additional actions: " + GitHubWorkspaceClient.actions.sorted().joinToString(", ")
                            )
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("search_repositories"))
                                    add(JsonPrimitive("search_issues"))
                                    add(JsonPrimitive("get_file_contents"))
                                    add(JsonPrimitive("get_issue"))
                                    add(JsonPrimitive("search_code"))
                                    add(JsonPrimitive("get_pull_request"))
                                    add(JsonPrimitive("list_pull_requests"))
                                    add(JsonPrimitive("list_workflow_runs"))
                                    add(JsonPrimitive("list_workflows"))
                                    add(JsonPrimitive("get_workflow_run"))
                                    add(JsonPrimitive("list_workflow_jobs"))
                                    add(JsonPrimitive("get_workflow_job"))
                                    add(JsonPrimitive("list_workflow_artifacts"))
                                    add(JsonPrimitive("get_job_logs"))
                                    add(JsonPrimitive("watch_checks"))
                                    add(JsonPrimitive("dispatch_workflow"))
                                    add(JsonPrimitive("rerun_workflow"))
                                    add(JsonPrimitive("rerun_failed_jobs"))
                                    add(JsonPrimitive("cancel_workflow"))

                                    add(JsonPrimitive("create_branch"))
                                    add(JsonPrimitive("update_file"))
                                    add(JsonPrimitive("create_pull_request"))
                                    GitHubWorkspaceClient.actions.sorted().forEach { add(JsonPrimitive(it)) }
                                }
                            )
                        }
                    )
                    listOf("run_id", "job_id", "release_id", "page", "pr_number").forEach { name ->
                        put(
                            name,
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                            }
                        )
                    }
                    put(
                        "workflow_id",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Discovered workflow ID, filename or .github/workflows/release-build.yml path. Used by dispatch_workflow and optionally publish_release/release_status.")
                        }
                    )
                    put(
                        "inputs",
                        buildJsonObject {
                            put("type", "object")
                            put("description", "Input values declared by workflow_dispatch in the workflow file.")
                        }
                    )
                    put(
                        "tag_name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Git tag for release actions, for example v0.9.30.0.")
                        }
                    )
                    put(
                        "target_commitish",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Branch, tag, or commit to target when creating a tag/release or dispatching a release workflow.")
                        }
                    )
                    put(
                        "release_name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Display name for a GitHub release. Defaults to tag_name.")
                        }
                    )
                    put(
                        "prerelease",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether the GitHub release is a prerelease.")
                        }
                    )
                    put(
                        "generate_release_notes",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Ask GitHub to generate release notes when creating a direct release.")
                        }
                    )
                    put(
                        "release_strategy",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("auto"))
                                    add(JsonPrimitive("workflow"))
                                    add(JsonPrimitive("direct"))
                                }
                            )
                            put("description", "publish_release strategy. auto prefers an active release workflow, workflow requires one, direct uses the Releases API.")
                        }
                    )
                    put(
                        "make_latest",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("true"))
                                    add(JsonPrimitive("false"))
                                    add(JsonPrimitive("legacy"))
                                }
                            )
                            put("description", "GitHub make_latest value for create_release/update_release.")
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
                            put("description", "Branch name, commit SHA, or tag. Used by code reads, workflow dispatch, release status, and release publication.")
                        }
                    )
                    put(
                        "issue_number",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Issue or pull request number. Required for get_issue.")
                        }
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "UTF-8 file content for update_file.")
                        }
                    )
                    put(
                        "message",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Commit message for update_file.")
                        }
                    )
                    put(
                        "branch",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Branch name for create_branch or update_file.")
                        }
                    )
                    put(
                        "base",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Base branch for create_branch or create_pull_request.")
                        }
                    )
                    put(
                        "head",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Head branch for create_pull_request or compare_refs.")
                        }
                    )
                    put(
                        "title",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Pull request title.")
                        }
                    )
                    put(
                        "body",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Pull request body.")
                        }
                    )
                    put(
                        "file_sha",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Existing blob SHA when replacing a file.")
                        }
                    )
                    listOf("start_line", "end_line").forEach { key ->
                        put(
                            key,
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                            }
                        )
                    }
                    put(
                        "draft",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Draft state. For create_pull_request it defaults to true; for release creation/publication it defaults to false.")
                        }
                    )
                    put(
                        "event",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Review event: APPROVE, REQUEST_CHANGES, or COMMENT.")
                        }
                    )
                    put(
                        "merge_method",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Merge method: squash, merge, or rebase.")
                        }
                    )
                    put(
                        "workflow_run_id",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Exact run ID from the workflow dispatch receipt; not an unrelated recent run.")
                        }
                    )
                    put(
                        "expected_head_sha",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Exact branch commit SHA read before preparing these changes; required for commit_files.")
                        }
                    )
                    put(
                        "files",
                        buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", 20)
                            put("description", "UTF-8 file replacements committed together on an explicit working branch.")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("path", buildJsonObject { put("type", "string") })
                                            put("content", buildJsonObject { put("type", "string") })
                                        }
                                    )
                                    put(
                                        "required",
                                        buildJsonArray {
                                            add(JsonPrimitive("path"))
                                            add(JsonPrimitive("content"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put(
                        "pull_number",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Pull request number.")
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
        if (credentialAdmissionFailed) return AgentToolResult(callId, ToolResultContent.Text("GitHub authorization could not be verified. Check the internet connection and reconnect GitHub if authorization expired. No remote request was dispatched; reconcile any previous uncertain write before retrying."), true, errorCode = "GITHUB_AUTHORIZATION_UNVERIFIED", dispatched = false)

        val repository = try {
            dev.chungjungsoo.gptmobile.data.github.normalizeGitHubRepository(
                (arguments["owner"] as? JsonPrimitive)?.content,
                (arguments["repo"] as? JsonPrimitive)?.content
            )
        } catch (invalid: IllegalArgumentException) {
            return errorResult(callId, invalid.message ?: "Invalid repository reference.")
        }
        repositoryScope?.let { selected ->
            val action = (arguments["action"] as? JsonPrimitive)?.content.orEmpty()
            if (action in setOf("search_repositories", "search_issues", "search_code", "list_repositories") || (action == "build_index" && !selected.prepareIndex)) {
                return AgentToolResult(callId, ToolResultContent.Text("This programmer conversation uses the selected repository only. Use pinned repository search; index preparation requires its workspace option."), true, errorCode = "REPOSITORY_SCOPE_MISMATCH", dispatched = false)
            }
            if ((repository.first != null && !repository.first.equals(selected.owner, true)) || (repository.second != null && !repository.second.equals(selected.repo, true))) {
                return AgentToolResult(callId, ToolResultContent.Text("This programmer conversation is bound to ${selected.owner}/${selected.repo}. Open a separate workspace to use another repository."), true, errorCode = "REPOSITORY_SCOPE_MISMATCH", dispatched = false)
            }
        }
        val normalizedArguments = buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            repository.first?.let { put("owner", it) }
            repository.second?.let { put("repo", it) }
        }
        val selectedContext = workspaceClient.taskContext() ?: repositoryContext
        val effectiveArguments = buildJsonObject {
            normalizedArguments.forEach { (key, value) -> put(key, value) }
            selectedContext?.let { selected ->
                // Fill repository context field-by-field. A model may provide only owner or only repo;
                // requiring both to be absent left the complementary field missing and caused fast
                // validation failures even though a selected repository was available.
                if (normalizedArguments["owner"] == null) put("owner", selected.owner)
                if (normalizedArguments["repo"] == null) put("repo", selected.repo)
                val effectiveOwner = normalizedArguments["owner"]?.jsonPrimitive?.content ?: selected.owner
                val effectiveRepo = normalizedArguments["repo"]?.jsonPrimitive?.content ?: selected.repo
                val sameRepo = effectiveOwner.equals(selected.owner, true) && effectiveRepo.equals(selected.repo, true)
                if (sameRepo && normalizedArguments["ref"] == null) put("ref", selected.ref)
            }
        }
        return executeEffective(callId, effectiveArguments)
    }

    private suspend fun executeEffective(callId: String, arguments: JsonObject): AgentToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content?.trim()
            ?: return errorResult(callId, "Missing required parameter: 'action'.")

        return try {
            if (action in GitHubWorkspaceClient.actions) {
                if (action == "delete_branch") {
                    (workspaceClient.taskContext() ?: repositoryContext)?.let { selected ->
                        val sameRepo = arguments["owner"]?.jsonPrimitive?.content.orEmpty().equals(selected.owner, true) &&
                            arguments["repo"]?.jsonPrimitive?.content.orEmpty().equals(selected.repo, true)
                        require(!sameRepo || arguments["branch"]?.jsonPrimitive?.content != selected.ref) { "Cannot delete the selected workspace branch. Select another branch first." }
                    }
                }
                if (action == "commit_files") {
                    (workspaceClient.taskContext() ?: repositoryContext)?.let { selected ->
                        val sameRepo = arguments["owner"]?.jsonPrimitive?.content.orEmpty().equals(selected.owner, true) &&
                            arguments["repo"]?.jsonPrimitive?.content.orEmpty().equals(selected.repo, true)
                        require(!sameRepo || arguments["branch"]?.jsonPrimitive?.content == selected.ref) { "The write branch disagrees with the selected workspace. Select the intended branch first." }
                    }
                }
                val result = workspaceClient.execute(action, arguments)
                return successResult(callId, result.toString())
            }
            when (action) {
                "watch_checks" -> successResult(callId, requireNotNull(watchChecks) { "Background check subscriptions require a connected account and saved chat." }(arguments).toString())
                "search_repositories" -> handleSearchRepositories(callId, arguments)
                "search_issues" -> handleSearchIssues(callId, arguments)
                "get_file_contents" -> handleGetFileContents(callId, arguments)
                "get_issue" -> handleGetIssue(callId, arguments)
                "search_code" -> handleSearchCode(callId, arguments)
                "get_pull_request" -> handleGetPullRequest(callId, arguments)
                "list_pull_requests" -> handleListPullRequests(callId, arguments)
                "list_workflow_runs" -> handleListWorkflowRuns(callId, arguments)
                "list_workflows", "get_workflow_run", "list_workflow_jobs", "get_workflow_job", "list_workflow_artifacts", "get_job_logs", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow" -> handleWorkflowAction(callId, action, arguments)
                "create_branch" -> handleCreateBranch(callId, arguments)
                "create_issue_comment" -> handleIssueComment(callId, arguments)
                "update_file" -> {
                    require(engineeringPrepare == null) { "Use stage_patch and commit_staged_patch to submit the exact patch for persisted user approval in GitHub review." }
                    handleUpdateFile(callId, arguments)
                }
                "create_pull_request" -> handleCreatePullRequest(callId, arguments)
                else -> errorResult(callId, "Unknown GitHub action: '$action'.")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            classifyGitHubFailure(callId, action, arguments, throwable)
        }
    }

    private suspend fun handleSearchRepositories(callId: String, arguments: JsonObject): AgentToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
        if (query.isNullOrEmpty()) {
            return errorResult(callId, "Parameter 'query' is required for action 'search_repositories'.")
        }
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val page = (arguments["page"]?.jsonPrimitive?.intOrNull ?: 1).also { require(it in 1..34) }
        val url = "$BASE_URL/search/repositories?q=$encodedQuery&per_page=30&page=$page"
        val response = getGitHubApi(url)
        val text = response.bodyAsText()

        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }

        val json = jsonParser.parseToJsonElement(text).jsonObject
        val items = json["items"]?.jsonArray ?: JsonArray(emptyList())
        val summary = buildJsonObject {
            put("total_count", json["total_count"] ?: JsonNull)
            put("incomplete_results", json["incomplete_results"] ?: JsonNull)
            put("page", page)
            val more = page * 30 < minOf(json["total_count"]?.jsonPrimitive?.intOrNull ?: 0, 1000)
            put("has_more", more)
            put("search_limit", 1000)
            if (more) {
                put(
                    "continuation",
                    buildJsonObject {
                        put("action", "search_repositories")
                        put("query", query)
                        put("page", page + 1)
                    }
                )
            }
            put(
                "items",
                buildJsonArray {
                    items.forEach { item ->
                        val obj = item.jsonObject
                        add(
                            buildJsonObject {
                                listOf("id", "node_id", "owner", "fork", "forks_count", "default_branch", "languages_url", "updated_at", "pushed_at", "visibility", "archived").forEach { key -> obj[key]?.let { put(key, it) } }
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
        val encodedQuery = URLEncoder.encode(dev.chungjungsoo.gptmobile.data.github.normalizeGitHubIssueQuery(query), StandardCharsets.UTF_8.name())
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

    private suspend fun handleSearchCode(callId: String, arguments: JsonObject): AgentToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) {
            return errorResult(callId, "Parameter 'query' is required.")
        }
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim()
        val scoped =
            if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
                "$query repo:$owner/$repo"
            } else {
                query
            }
        val response = getGitHubApi(
            "$BASE_URL/search/code?q=${URLEncoder.encode(scoped, StandardCharsets.UTF_8.name())}&per_page=20"
        )
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }
        val json = jsonParser.parseToJsonElement(text).jsonObject
        val summary = buildJsonObject {
            put("total_count", json["total_count"] ?: JsonPrimitive(0))
            put(
                "items",
                buildJsonArray {
                    (json["items"]?.jsonArray ?: JsonArray(emptyList())).take(20).forEach { item ->
                        val obj = item.jsonObject
                        add(
                            buildJsonObject {
                                put("name", obj["name"] ?: JsonPrimitive(""))
                                put("path", obj["path"] ?: JsonPrimitive(""))
                                put("html_url", obj["html_url"] ?: JsonPrimitive(""))
                                put("repository", obj["repository"]?.jsonObject?.get("full_name") ?: JsonPrimitive(""))
                            }
                        )
                    }
                }
            )
        }
        return successResult(callId, summary.toString())
    }

    private suspend fun handleListPullRequests(callId: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (owner.isEmpty() || repo.isEmpty()) {
            return errorResult(callId, "Parameters 'owner' and 'repo' are required.")
        }
        val page = arguments["page"]?.jsonPrimitive?.intOrNull ?: 1
        require(page in 1..1000)
        val state = arguments["state"]?.jsonPrimitive?.content ?: "all"
        require(state in setOf("open", "closed", "all")) { "state must be open, closed or all." }
        val response = getGitHubApi("$BASE_URL/repos/$owner/$repo/pulls?state=$state&per_page=20&page=$page")
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }
        val items = jsonParser.parseToJsonElement(text).jsonArray
        val summary = buildJsonArray {
            items.forEach { item ->
                add(
                    buildJsonObject {
                        listOf("number", "title", "state", "draft", "html_url").forEach { key -> item.jsonObject[key]?.let { put(key, it) } }
                        listOf("head", "base").forEach { key -> item.jsonObject[key]?.let { put(key, compactGitRef(it.jsonObject)) } }
                    }
                )
            }
        }
        return successResult(
            callId,
            buildJsonObject {
                put("pull_requests", summary)
                put("page", page)
                put("per_page", 20)
                put("has_more", items.size == 20 && page < 1000)
                if (items.size == 20 && page < 1000) {
                    put(
                        "continuation",
                        buildJsonObject {
                            put("action", "list_pull_requests")
                            put("owner", owner)
                            put("repo", repo)
                            put("state", state)
                            put("page", page + 1)
                        }
                    )
                }
            }.toString()
        )
    }

    private suspend fun handleGetPullRequest(callId: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        val number = arguments["pull_number"]?.jsonPrimitive?.intOrNull ?: arguments["issue_number"]?.jsonPrimitive?.intOrNull
        if (owner.isEmpty() || repo.isEmpty() || number == null) {
            return errorResult(callId, "Parameters 'owner', 'repo', and 'pull_number' are required.")
        }
        val response = getGitHubApi("$BASE_URL/repos/$owner/$repo/pulls/$number")
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }
        val result = jsonParser.parseToJsonElement(text).jsonObject
        val summary = buildJsonObject {
            listOf("number", "title", "state", "draft", "html_url", "mergeable", "mergeable_state", "merged", "changed_files").forEach { key -> result[key]?.let { put(key, it) } }
            listOf("head", "base").forEach { key -> result[key]?.let { put(key, compactGitRef(it.jsonObject)) } }
            put("body", truncate(result["body"]?.jsonPrimitive?.content.orEmpty(), 8000))
        }
        return successResult(callId, summary.toString())
    }

    private suspend fun handleListWorkflowRuns(callId: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (owner.isEmpty() || repo.isEmpty()) {
            return errorResult(callId, "Parameters 'owner' and 'repo' are required.")
        }
        val page = arguments["page"]?.jsonPrimitive?.intOrNull ?: 1
        val perPage = arguments["per_page"]?.jsonPrimitive?.intOrNull ?: 100
        require(page in 1..1000 && perPage in 1..100) { "page must be 1–1000 and per_page 1–100." }
        val filters = listOf("branch", "status", "actor", "event", "created", "head_sha").mapNotNull { key ->
            arguments[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap().toMutableMap()
        arguments["commit_sha"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { filters["head_sha"] = it }
        val workflow = arguments["workflow_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val route = if (workflow == null) "runs" else "workflows/${normalizeGitHubWorkflowSelector(workflow)}/runs"
        val query = buildList {
            add("per_page=$perPage")
            add("page=$page")
            filters.forEach { (key, value) -> add("$key=${GitHubWorkspaceClient.segment(value)}") }
        }.joinToString("&")
        val response = getGitHubApi("$BASE_URL/repos/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}/actions/$route?$query")
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return errorResult(callId, "GitHub API returned HTTP ${response.status.value}: ${truncate(text, 500)}")
        }
        val json = jsonParser.parseToJsonElement(text).jsonObject
        val runs = json["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
        val total = json["total_count"]?.jsonPrimitive?.intOrNull
        val searchLimit = if (filters.isNotEmpty()) 1000 else Int.MAX_VALUE
        val more = if (total != null) page * perPage < minOf(total, searchLimit) else runs.size == perPage && page * perPage < searchLimit
        val compact = buildJsonObject {
            put("total_count", json["total_count"] ?: JsonNull)
            put("page", page)
            put("per_page", perPage)
            put("returned", runs.size)
            put("has_more", more)
            put("history_truncated", total == null || total > searchLimit)
            put("traversal_complete", !more)
            put("history_complete", page == 1 && !more && total != null && runs.size == total && total <= searchLimit)
            put("completion_note", "This response contains one page. Consuming the final page alone does not prove earlier pages were read; use the resumable history collector for an audited total.")
            if (filters.isNotEmpty()) {
                put("search_limit", 1000)
                put("limit_note", "GitHub caps filtered run searches at 1,000 results. Narrow created to smaller date ranges for complete history.")
            }
            if (more) put("continuation", JsonObject(arguments + mapOf("action" to JsonPrimitive("list_workflow_runs"), "page" to JsonPrimitive(page + 1), "per_page" to JsonPrimitive(perPage))))
            put(
                "workflow_runs",
                buildJsonArray {
                    runs.forEach { item ->
                        add(
                            buildJsonObject {
                                listOf("id", "workflow_id", "name", "path", "display_title", "event", "status", "conclusion", "head_branch", "head_sha", "run_number", "run_attempt", "actor", "triggering_actor", "head_commit", "run_started_at", "created_at", "updated_at", "html_url", "jobs_url", "logs_url", "artifacts_url").forEach { key -> item.jsonObject[key]?.let { put(key, it) } }
                            }
                        )
                    }
                }
            )
        }
        return successResult(callId, compact.toString())
    }

    private suspend fun handleWorkflowAction(callId: String, action: String, arguments: JsonObject): AgentToolResult {
        val owner = arguments["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content.orEmpty()
        require(Regex("[A-Za-z0-9_.-]+").matches(owner) && Regex("[A-Za-z0-9_.-]+").matches(repo)) { "Valid owner and repo are required." }
        fun positiveId(name: String): Long = requireNotNull(arguments[name]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0 }) { "A positive $name is required." }
        val base = "$BASE_URL/repos/$owner/$repo/actions"
        val page = arguments["page"]?.jsonPrimitive?.intOrNull ?: 1
        require(page in 1..1000) { "Page must be between 1 and 1000." }
        val perPage = arguments["per_page"]?.jsonPrimitive?.intOrNull ?: 30
        require(perPage in 1..100) { "per_page must be between 1 and 100." }
        val pagination = "?per_page=$perPage&page=$page"
        val write = action in setOf("dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow")
        if (write) requireToken()
        if (action == "get_job_logs") {
            val jobId = positiveId("job_id")
            if (arguments["run_id"] != null || arguments["expected_head_sha"] != null) {
                val response = getGitHubApi("$base/jobs/$jobId")
                val text = response.bodyAsText()
                if (!response.status.isSuccess()) return classifyGitHubHttpFailure(callId, action, arguments, response, text)
                val job = jsonParser.parseToJsonElement(text).jsonObject
                arguments["run_id"]?.let { require(job["run_id"] == it) { "Job belongs to another workflow run; rediscover jobs using run_id." } }
                arguments["expected_head_sha"]?.let { require(job["head_sha"] == it) { "CI evidence belongs to a different commit; refresh the job head." } }
            }
            val log = dev.chungjungsoo.gptmobile.data.github.GitHubCiLogReader(httpClient, apiToken, scheduler, responseCache, authenticationRejected = authenticationRejected)
                .read("/repos/$owner/$repo/actions/jobs/$jobId/logs", immutable = false)
            val safe = dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage(log.text).let { if (apiToken.isNotBlank()) it.replace(apiToken, "[REDACTED]") else it }
            return successResult(callId, dev.chungjungsoo.gptmobile.data.github.GitHubCiLogPage.render(log.copy(text = safe), arguments).toString())
        }
        if (action == "dispatch_workflow" && arguments["workflow_id"]?.jsonPrimitive?.content.isNullOrBlank()) {
            val discovery = handleWorkflowAction(callId, "list_workflows", arguments)
            if (discovery.isError) return discovery
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("message", "Select an explicit workflow_id and ref from discovery before dispatching. No workflow was started.")
                        put(
                            "discovery",
                            when (val content = discovery.content) {
                                is ToolResultContent.Json -> content.value
                                is ToolResultContent.Text -> jsonParser.parseToJsonElement(content.text)
                                else -> JsonPrimitive("Use github_ci action=workflows.")
                            }
                        )
                    }
                ),
                true,
                errorCode = "WORKFLOW_SELECTION_REQUIRED",
                dispatched = false
            )
        }
        if (write) workflowWritePrecondition(callId, action, arguments, base)?.let { return it }
        val path = when (action) {
            "list_workflows" -> "/workflows$pagination"
            "get_workflow_run" -> "/runs/${positiveId("run_id")}"
            "list_workflow_jobs" -> "/runs/${positiveId("run_id")}/jobs$pagination"
            "get_workflow_job" -> "/jobs/${positiveId("job_id")}"
            "list_workflow_artifacts" -> "/runs/${positiveId("run_id")}/artifacts$pagination"
            "dispatch_workflow" -> {
                val workflow = normalizeGitHubWorkflowSelector(arguments["workflow_id"]?.jsonPrimitive?.content.orEmpty())
                "/workflows/$workflow/dispatches"
            }
            "rerun_workflow" -> "/runs/${positiveId("run_id")}/rerun"
            "rerun_failed_jobs" -> "/runs/${positiveId("run_id")}/rerun-failed-jobs"
            "cancel_workflow" -> "/runs/${positiveId("run_id")}/cancel"
            else -> error("Unsupported workflow action.")
        }
        val response = if (write) {
            val body = if (action == "dispatch_workflow") {
                buildJsonObject {
                    val ref = arguments["ref"]?.jsonPrimitive?.content.orEmpty()
                    require(ref.isNotBlank()) { "A branch or tag ref is required for dispatch_workflow." }
                    put("ref", ref)
                    put("return_run_details", true)
                    arguments["inputs"]?.let {
                        require(it is JsonObject) { "Inputs must be an object." }
                        put("inputs", it)
                    }
                }
            } else {
                buildJsonObject {}
            }
            writeGitHubApi(base + path, HttpMethod.Post, body)
        } else {
            getGitHubApi(base + path)
        }
        val text = response.bodyAsText()
        return if (response.status.isSuccess()) {
            if (action == "get_workflow_job") {
                val job = jsonParser.parseToJsonElement(text).jsonObject
                arguments["run_id"]?.let { require(job["run_id"] == it) { "Job belongs to another workflow run; rediscover jobs using run_id." } }
                arguments["expected_head_sha"]?.let { require(job["head_sha"] == it) { "CI evidence belongs to a different commit; refresh the job head." } }
            }
            successResult(
                callId,
                if (write) {
                    val receipt = text.takeIf { it.isNotBlank() }?.let { jsonParser.parseToJsonElement(it) as? JsonObject }
                    buildJsonObject {
                        put("status", "accepted")
                        put("action", action)
                        put("upstream_completed", true)
                        put("message", "GitHub accepted $action. Acceptance does not mean that the workflow completed. Reconcile this receipt before retrying a mutation.")
                        listOf("run_id", "workflow_id", "ref").forEach { key -> arguments[key]?.let { put(key, it) } }
                        receipt?.let { put("receipt", it) }
                        val runId = receipt?.get("workflow_run_id") ?: receipt?.get("id") ?: arguments["run_id"]
                        if (runId != null) {
                            put(
                                "continuation",
                                buildJsonObject {
                                    put("action", "get_workflow_run")
                                    put("run_id", runId)
                                }
                            )
                        } else {
                            put("association", "unknown; no run ID returned by GitHub. Inspect action=runs for the selected workflow/ref; do not infer a run ID from recency alone.")
                        }
                    }.toString()
                } else {
                    compactWorkflowPayload(action, text, page, perPage, arguments)
                }
            )
        } else {
            classifyGitHubHttpFailure(callId, action, arguments, response, text)
        }
    }

    private fun compactWorkflowPayload(action: String, text: String, page: Int, perPage: Int, arguments: JsonObject): String {
        val parsed = runCatching { jsonParser.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: error("GITHUB_UNEXPECTED_RESPONSE · Expected a GitHub CI JSON object; no empty collection was inferred.")

        fun projectedArray(key: String, fields: List<String>): JsonObject {
            val values = checkNotNull(parsed[key] as? JsonArray) { "GITHUB_UNEXPECTED_RESPONSE · GitHub response is missing the $key collection; this is not an empty result." }
            return buildJsonObject {
                put("total_count", parsed["total_count"] ?: JsonNull)
                put("page", page)
                put("per_page", perPage)
                put("returned", values.size)
                put("empty", values.isEmpty())
                val total = parsed["total_count"]?.jsonPrimitive?.intOrNull
                val hasMore = if (total != null) page * perPage < total else values.size == perPage
                put("pagination_inconsistent", values.isEmpty() && total != null && total > (page - 1) * perPage)
                put("has_more", hasMore)
                if (hasMore) put("continuation", JsonObject(arguments + mapOf("action" to JsonPrimitive(action), "page" to JsonPrimitive(page + 1), "per_page" to JsonPrimitive(perPage))))
                put(
                    key,
                    buildJsonArray {
                        values.forEach { value ->
                            add(
                                buildJsonObject {
                                    fields.forEach { field -> value.jsonObject[field]?.let { put(field, it) } }
                                }
                            )
                        }
                    }
                )
            }
        }

        return when (action) {
            "list_workflows" -> projectedArray(
                "workflows",
                listOf("id", "name", "path", "state", "html_url", "created_at", "updated_at")
            ).toString()
            "get_workflow_run" -> buildJsonObject {
                listOf(
                    "id", "name", "event", "status", "conclusion", "head_branch", "head_sha",
                    "workflow_id", "path", "run_number", "run_attempt", "run_started_at", "created_at", "updated_at", "html_url", "jobs_url", "logs_url", "artifacts_url"
                ).forEach { field -> parsed[field]?.let { put(field, it) } }
            }.toString()
            "get_workflow_job" -> parsed.toString()
            "list_workflow_jobs" -> projectedArray(
                "jobs",
                listOf("id", "run_id", "head_sha", "name", "status", "conclusion", "started_at", "completed_at", "steps", "runner_name", "runner_group_name", "labels", "html_url")
            ).toString()
            "list_workflow_artifacts" -> projectedArray(
                "artifacts",
                listOf("id", "name", "size_in_bytes", "expired", "created_at", "expires_at", "digest", "workflow_run", "archive_download_url")
            ).toString()
            else -> truncate(text, MAX_OUTPUT_CHARS)
        }
    }

    /** Observe state before a CI mutation; never cancel or replay another run to force progress. */
    private suspend fun workflowWritePrecondition(callId: String, action: String, args: JsonObject, base: String): AgentToolResult? {
        fun stopped(code: String, message: String, observation: JsonObject? = null) = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    observation?.let { put("observation", it) }
                }
            ),
            true,
            errorCode = code,
            dispatched = false
        )
        if (action == "dispatch_workflow") {
            val workflow = normalizeGitHubWorkflowSelector(args["workflow_id"]?.jsonPrimitive?.content.orEmpty())
            require(!args["ref"]?.jsonPrimitive?.content.isNullOrBlank()) { "An explicit ref is required for workflow dispatch." }
            val response = getGitHubApi("$base/workflows/$workflow")
            val text = response.bodyAsText()
            if (response.status.value == 404 && workflow.toLongOrNull()?.let { it > 0 } == true) {
                val runResponse = getGitHubApi("$base/runs/$workflow")
                val runText = runResponse.bodyAsText()
                if (runResponse.status.isSuccess()) {
                    val run = jsonParser.parseToJsonElement(runText).jsonObject
                    return stopped(
                        "WORKFLOW_ID_IS_RUN_ID",
                        "workflow_id is a run ID. Discover workflows and use the observed workflow_id or YAML filename with the explicit ref. No workflow was dispatched.",
                        buildJsonObject {
                            put("run_id", args.getValue("workflow_id"))
                            put("workflow_id", run["workflow_id"] ?: JsonNull)
                            put("path", run["path"] ?: JsonNull)
                        }
                    )
                }
            }
            if (!response.status.isSuccess()) return classifyGitHubHttpFailure(callId, action, args, response, text)
            val workflowState = jsonParser.parseToJsonElement(text).jsonObject
            if (workflowState["state"]?.jsonPrimitive?.content != "active") {
                return stopped("WORKFLOW_NOT_ACTIVE", "This workflow is disabled or its state is unavailable. Inspect action=workflows before dispatching; no workflow was started.", workflowState)
            }
            return null
        }
        val runId = requireNotNull(args["run_id"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0 }) { "A positive run_id is required." }
        val response = getGitHubApi("$base/runs/$runId")
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) return classifyGitHubHttpFailure(callId, action, args, response, text)
        val run = jsonParser.parseToJsonElement(text).jsonObject
        val status = run["status"]?.jsonPrimitive?.content.orEmpty()
        if (status == "completed" && action == "cancel_workflow") {
            return successResult(
                callId,
                buildJsonObject {
                    put("status", "already_completed")
                    put("run_id", runId)
                    put("message", "The run has completed; no cancellation was sent.")
                    put(
                        "continuation",
                        buildJsonObject {
                            put("action", "get_workflow_run")
                            put("run_id", runId)
                        }
                    )
                }.toString()
            ).copy(dispatched = false)
        }
        if (status.isBlank()) return stopped("CI_STATE_UNAVAILABLE", "The run state is unavailable. Inspect action=run before retrying; no mutation was sent.")
        if (status !in setOf("completed", "queued", "in_progress", "pending", "requested", "waiting")) {
            return stopped("CI_STATE_UNAVAILABLE", "The run returned an unrecognized state. Inspect action=run before retrying; no mutation was sent.")
        }
        if (action != "cancel_workflow" && status != "completed") {
            return stopped("CI_RUN_ACTIVE", "The run is $status. Wait for completion before rerunning; no retry or cancellation was sent.", run)
        }
        if (action == "rerun_failed_jobs" && run["conclusion"] == JsonPrimitive("success")) {
            return successResult(
                callId,
                buildJsonObject {
                    put("status", "no_failed_jobs")
                    put("run_id", runId)
                    put("message", "This completed run succeeded. No failed-job rerun was sent; use rerun_all only when a complete rerun is intended.")
                }.toString()
            ).copy(dispatched = false)
        }
        return null
    }

    private suspend fun handleCreateBranch(callId: String, arguments: JsonObject): AgentToolResult {
        requireToken()
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        val branch = arguments["branch"]?.jsonPrimitive?.content?.trim().orEmpty()
        val base = arguments["base"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "main" }
        if (owner.isEmpty() || repo.isEmpty() || branch.isEmpty()) {
            return errorResult(callId, "Parameters 'owner', 'repo', and 'branch' are required.")
        }
        val baseResponse = getGitHubApi(
            "$BASE_URL/repos/$owner/$repo/git/ref/heads/${URLEncoder.encode(base, StandardCharsets.UTF_8.name())}"
        )
        val baseText = baseResponse.bodyAsText()
        if (!baseResponse.status.isSuccess()) {
            return errorResult(callId, "Could not resolve base branch: ${truncate(baseText, 500)}")
        }
        val sha = jsonParser.parseToJsonElement(baseText).jsonObject["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
            ?: return errorResult(callId, "Base branch did not return a commit SHA.")
        require(arguments["expected_head_sha"]?.jsonPrimitive?.content.let { it.isNullOrBlank() || it == sha }) { "The base branch changed; resolve its current head before creating a branch." }
        val response = writeGitHubApi(
            "$BASE_URL/repos/$owner/$repo/git/refs",
            HttpMethod.Post,
            buildJsonObject {
                put("ref", "refs/heads/$branch")
                put("sha", sha)
            }
        )
        val text = response.bodyAsText()
        if (response.status.value == 422 && runCatching { jsonParser.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull().equals("Reference already exists", true)) {
            val existingResponse = getGitHubApi("$BASE_URL/repos/$owner/$repo/git/ref/heads/${GitHubWorkspaceClient.segment(branch)}")
            val existingText = existingResponse.bodyAsText()
            if (!existingResponse.status.isSuccess()) return classifyGitHubHttpFailure(callId, "create_branch", arguments, existingResponse, existingText)
            val existing = jsonParser.parseToJsonElement(existingText).jsonObject
            val existingSha = existing["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
            require(existing["ref"]?.jsonPrimitive?.content == "refs/heads/$branch" && existingSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Existing branch could not be verified." }
            return AgentToolResult(
                callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("branch", branch)
                        put("already_exists", true)
                        put("created", false)
                        put("head_sha", existingSha)
                        put("matches_requested_base", existingSha == sha)
                        put("message", "The branch already exists. Its head was read and no ref was changed. Select it explicitly to continue, or choose a different new branch name.")
                        put(
                            "recovery_call",
                            buildJsonObject {
                                put("tool", "github_context" + modelToolName.removePrefix("github"))
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        put("action", "resolve")
                                        put("owner", owner)
                                        put("repo", repo)
                                        put("ref", branch)
                                        put("refresh", true)
                                    }
                                )
                            }
                        )
                    }
                ),
                isError = false
            )
        }
        return if (response.status.isSuccess()) {
            val receipt = jsonParser.parseToJsonElement(text).jsonObject
            val selection = try {
                workspaceClient.execute(
                    "resolve_context",
                    buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("ref", branch)
                        put("refresh", true)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            successResult(
                callId,
                buildJsonObject {
                    put("branch", branch)
                    put("receipt", receipt)
                    put("head_sha", receipt["object"]?.jsonObject?.get("sha") ?: JsonNull)
                    put("expected_head_sha", receipt["object"]?.jsonObject?.get("sha") ?: JsonNull)
                    put("base_sha", sha)
                    put("receipt_verified", receipt["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content == sha && receipt["ref"]?.jsonPrimitive?.content == "refs/heads/$branch")
                    put("created", true)
                    put("upstream_completed", true)
                    selection?.let { put("context", it) } ?: put("context_refresh_required", true)
                }.toString()
            )
        } else {
            classifyGitHubHttpFailure(callId, "create_branch", arguments, response, text)
        }
    }

    private suspend fun handleIssueComment(callId: String, arguments: JsonObject): AgentToolResult {
        requireToken()
        val owner = arguments["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content.orEmpty()
        val number = arguments["pull_number"]?.jsonPrimitive?.intOrNull ?: return errorResult(callId, "pull_number is required.")
        val body = arguments["body"]?.jsonPrimitive?.content.orEmpty()
        require(owner.isNotBlank() && repo.isNotBlank() && number > 0 && body.isNotBlank()) { "Repository, pull request and comment are required." }
        val response = writeGitHubApi("$BASE_URL/repos/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}/issues/$number/comments", HttpMethod.Post, buildJsonObject { put("body", body) })
        val text = response.bodyAsText()
        return if (response.status.isSuccess()) successResult(callId, text) else classifyGitHubHttpFailure(callId, "create_issue_comment", arguments, response, text)
    }

    private suspend fun handleUpdateFile(callId: String, arguments: JsonObject): AgentToolResult {
        requireToken()
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        val path = arguments["path"]?.jsonPrimitive?.content?.trim().orEmpty().removePrefix("/")
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return errorResult(callId, "Parameter 'content' is required.")
        val message = arguments["message"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "Update $path" }
        val branch = arguments["branch"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return errorResult(callId, "An explicit working branch and expected_head_sha are required for update_file.")
        repositoryContext?.let { selected ->
            if (owner.equals(selected.owner, true) && repo.equals(selected.repo, true) && branch != selected.ref) {
                return errorResult(callId, "The write branch disagrees with the selected workspace. Select the intended branch first.")
            }
        }
        if (owner.isEmpty() || repo.isEmpty() || path.isEmpty()) {
            return errorResult(callId, "Parameters 'owner', 'repo', and 'path' are required.")
        }
        val guarded = buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("branch", branch)
            put("message", message)
            put(
                "files",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("content", content)
                        }
                    )
                }
            )
        }
        return successResult(callId, workspaceClient.execute("commit_files", guarded).toString())
    }

    private suspend fun handleCreatePullRequest(callId: String, arguments: JsonObject): AgentToolResult {
        requireToken()
        val owner = arguments["owner"]?.jsonPrimitive?.content?.trim().orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content?.trim().orEmpty()
        val title = arguments["title"]?.jsonPrimitive?.content?.trim().orEmpty()
        val head = arguments["head"]?.jsonPrimitive?.content?.trim().orEmpty()
        val base = arguments["base"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "main" }
        val bodyText = arguments["body"]?.jsonPrimitive?.content.orEmpty()
        if (owner.isEmpty() || repo.isEmpty() || title.isEmpty() || head.isEmpty()) {
            return errorResult(callId, "Parameters 'owner', 'repo', 'title', and 'head' are required.")
        }
        val response = writeGitHubApi(
            "$BASE_URL/repos/$owner/$repo/pulls",
            HttpMethod.Post,
            buildJsonObject {
                put("title", title)
                put("head", head)
                put("base", base)
                put("body", bodyText)
                put("draft", arguments["draft"] ?: JsonPrimitive(true))
            }
        )
        val text = response.bodyAsText()
        return if (response.status.isSuccess()) {
            val pr = jsonParser.parseToJsonElement(text).jsonObject
            successResult(
                callId,
                buildJsonObject {
                    put("pull_request", pr)
                    put("upstream_completed", true)
                    put("readiness", "not_observed")
                    put(
                        "continuation",
                        buildJsonObject {
                            put("action", "pr_context")
                            put("pull_number", pr["number"] ?: JsonNull)
                            put("expected_head_sha", pr["head"]?.jsonObject?.get("sha") ?: JsonNull)
                            put("include_checks", true)
                            put("include_health", true)
                        }
                    )
                }.toString()
            )
        } else {
            classifyGitHubHttpFailure(callId, "create_pull_request", arguments, response, text)
        }
    }

    private suspend fun classifyGitHubFailure(
        callId: String,
        action: String,
        arguments: JsonObject,
        throwable: Throwable
    ): AgentToolResult {
        val detail = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
        if (detail.startsWith("GITHUB_UNEXPECTED_RESPONSE")) {
            return AgentToolResult(callId, ToolResultContent.Text(detail), true, errorCode = "GITHUB_UNEXPECTED_RESPONSE")
        }
        if (Regex("(?i)create a working branch before committing|use a working branch; direct default-branch writes").containsMatchIn(detail)) {
            return AgentToolResult(callId, ToolResultContent.Text("WORKING_BRANCH_REQUIRED · Create/select a permitted branch with github_commit action=branch, resolve that branch, then stage against its current head. The preview is retained; no commit was sent. Repository write access does not authorize direct default-branch writes."), true, errorCode = "WORKING_BRANCH_REQUIRED", dispatched = false)
        }
        if (detail.contains("Pull request head changed", ignoreCase = true)) {
            return AgentToolResult(callId, ToolResultContent.Text("GITHUB_PR_HEAD_CHANGED · Refresh github_pr action=context and review the current diff before supplying a new expected_head_sha. No PR mutation was sent."), true, errorCode = "GITHUB_PR_HEAD_CHANGED", dispatched = false)
        }
        if (throwable is IllegalArgumentException) {
            val explanation = detail.takeUnless { it.isBlank() || it == "Failed requirement." }
                ?: "Invalid or missing arguments for this GitHub operation."
            return errorResult(
                callId,
                "GITHUB_INVALID_ARGUMENT · action=$action · $explanation " +
                    "Check the action's required fields and types, then retry with corrected arguments."
            )
        }
        if (detail.contains("HTTP 401", ignoreCase = true)) {
            return errorResult(callId, "GITHUB_AUTHENTICATION_REQUIRED · $detail")
        }
        if (detail.contains("HTTP 429", ignoreCase = true) ||
            detail.contains("HTTP 403", ignoreCase = true) &&
            (detail.contains("rateLimitRemaining=0") || detail.contains("secondary rate", ignoreCase = true))
        ) {
            return errorResult(callId, "GITHUB_RATE_LIMITED · $detail")
        }
        if (detail.contains("HTTP 403", ignoreCase = true) &&
            (detail.contains("sso=", ignoreCase = true) || detail.contains("SAML", ignoreCase = true))
        ) {
            return errorResult(callId, "GITHUB_SSO_REQUIRED · $detail")
        }
        if (action in MUTATING_ACTIONS && Regex("(?i)protected branch|branch protection|repository rule|GH006|GH013").containsMatchIn(detail)) {
            return errorResult(callId, "GITHUB_BRANCH_RULES_BLOCKED · $detail. Use a permitted working branch and PR; changing token scope does not override repository rules.")
        }
        if (action in MUTATING_ACTIONS && detail.contains("HTTP 403", ignoreCase = true)) {
            return errorResult(callId, writeDeniedMessage(action, arguments, detail))
        }
        if (detail.contains("HTTP 404", ignoreCase = true) || detail.contains("Not Found", ignoreCase = true)) {
            return errorResult(callId, notFoundMessage(action, arguments, detail))
        }
        return errorResult(callId, "GitHub request failed: $detail")
    }

    private suspend fun classifyGitHubHttpFailure(
        callId: String,
        action: String,
        arguments: JsonObject,
        response: HttpResponse,
        body: String
    ): AgentToolResult {
        val githubMessage = runCatching {
            jsonParser.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val detail = buildString {
            append("GitHub API returned HTTP ")
            append(response.status.value)
            githubMessage?.let {
                append(" · message=")
                append(it)
            }
            response.headers["X-RateLimit-Remaining"]?.let {
                append(" · rateLimitRemaining=")
                append(it)
            }
            response.headers["X-Accepted-GitHub-Permissions"]?.takeIf { it.isNotBlank() }?.let {
                append(" · acceptedGitHubPermissions=")
                append(it)
            }
            response.headers["X-OAuth-Scopes"]?.takeIf { it.isNotBlank() }?.let {
                append(" · oauthScopes=")
                append(it)
            }
        }
        return when {
            action in setOf("rerun_workflow", "rerun_failed_jobs", "cancel_workflow") &&
                response.status.value in setOf(403, 409, 422) &&
                Regex("(?i)already running|currently running|in progress|not completed|not running|already completed|unable to cancel").containsMatchIn(githubMessage.orEmpty()) ->
                AgentToolResult(callId, ToolResultContent.Text("CI_STATE_CHANGED · $detail. Refresh github_ci action=run with the same run_id. Do not cancel another run or blindly retry this mutation."), true, errorCode = "CI_STATE_CHANGED")
            action == "create_pull_request" && response.status.value == 422 ->
                AgentToolResult(callId, ToolResultContent.Text("GITHUB_PR_VALIDATION_FAILED · $detail. Inspect github_context action=branches and github_pr action=list; head/base must exist, differ and contain changes. A PR may already exist. No PR creation was confirmed. Details: ${truncate(body, 500)}"), true, errorCode = "GITHUB_PR_VALIDATION_FAILED")
            response.status.value == 429 ||
                response.status.value == 403 &&
                (response.headers["X-RateLimit-Remaining"] == "0" || githubMessage?.contains("secondary rate", ignoreCase = true) == true) ->
                errorResult(callId, "GITHUB_RATE_LIMITED · $detail. Wait for the rate-limit reset before retrying; repository write permission has not been revoked.")
            response.status.value == 403 &&
                (response.headers["X-GitHub-SSO"] != null || githubMessage?.contains("SAML", ignoreCase = true) == true) ->
                errorResult(callId, "GITHUB_SSO_REQUIRED · $detail. Authorize this GitHub App or token with the organization before retrying.")
            action in MUTATING_ACTIONS &&
                response.status.value in setOf(403, 409, 422) &&
                Regex("(?i)protected branch|branch protection|repository rule|GH006|GH013").containsMatchIn(githubMessage.orEmpty()) ->
                errorResult(callId, "GITHUB_BRANCH_RULES_BLOCKED · $detail. Use a permitted working branch and PR; changing token scope does not override repository rules.")
            action in MUTATING_ACTIONS && response.status.value == 403 ->
                errorResult(callId, writeDeniedMessage(action, arguments, detail))
            response.status.value == 404 ->
                errorResult(callId, notFoundMessage(action, arguments, detail))
            else ->
                errorResult(callId, "$detail: ${truncate(body, 500)}")
        }
    }

    private suspend fun notFoundMessage(
        action: String,
        arguments: JsonObject,
        githubDetail: String
    ): String {
        val owner = arguments["owner"]?.jsonPrimitive?.content.orEmpty()
        val repo = arguments["repo"]?.jsonPrimitive?.content.orEmpty()
        val ref = arguments["ref"]?.jsonPrimitive?.content
            ?: arguments["branch"]?.jsonPrimitive?.content
            ?: arguments["head"]?.jsonPrimitive?.content
        val path = arguments["path"]?.jsonPrimitive?.content
        val target = buildString {
            if (owner.isNotBlank() && repo.isNotBlank()) append("$owner/$repo")
            ref?.takeIf { it.isNotBlank() }?.let { append(" ref=").append(it.take(160)) }
            path?.takeIf { it.isNotBlank() }?.let { append(" path=").append(it.take(240)) }
        }.ifBlank { "requested GitHub resource" }

        val capability = if (action in MUTATING_ACTIONS) {
            runCatching { workspaceClient.execute("write_capabilities", arguments).jsonObject }.getOrNull()
        } else {
            null
        }
        val writeCapability = capability?.get("write_capability")?.jsonPrimitive?.content ?: "unknown"
        val canPush = capability?.get("can_push")?.jsonPrimitive?.content ?: "unknown"
        val permissionHint = if (action in MUTATING_ACTIONS) {
            " required_permission=${requiredGitHubPermission(action)} · write_capability=$writeCapability · can_push=$canPush."
        } else {
            ""
        }
        return "GITHUB_NOT_FOUND_OR_HIDDEN · action=$action · target=$target.$permissionHint " +
            "GitHub can return HTTP 404 both when a repository/ref/path does not exist and when the authenticated credential cannot see that resource or endpoint. " +
            "Verify the repository, ref and path once; for writes also verify the plugin's repository access and required permission. " +
            "Do not blindly retry the same request. GitHub detail: $githubDetail"
    }

    private suspend fun writeDeniedMessage(
        action: String,
        arguments: JsonObject,
        githubDetail: String
    ): String {
        val capabilities = runCatching {
            workspaceClient.execute("write_capabilities", arguments).jsonObject
        }.getOrNull()
        val capability = capabilities?.get("write_capability")?.jsonPrimitive?.content ?: "unknown"
        val canPush = capabilities?.get("can_push")?.jsonPrimitive?.content ?: "unknown"
        val requiredPermission = if (action == "publish_release" && githubDetail.contains("Actions endpoint denied access")) "Actions: write" else requiredGitHubPermission(action)
        val diagnosis = when (capability) {
            "denied" ->
                "Repository-level permission check reports can_push=false. The authenticated GitHub actor does not have repository write authority."
            "allowed" ->
                "Repository-level permission check reports can_push=$canPush. The actor can write to the repository, but the current PAT/GitHub App can still lack the endpoint permission required for this operation, or repository/branch/SSO rules may block it."
            else ->
                "Repository-level write authority could not be determined. HTTP 403 alone does not prove the token is globally read-only."
        }
        return "GITHUB_WRITE_BLOCKED · action=$action · write_capability=$capability · can_push=$canPush · required_permission=$requiredPermission. " +
            "$diagnosis GitHub detail: $githubDetail. " +
            "Do not retry this write repeatedly and do not fall back to shell/terminal/git commands. " +
            "Use write_capabilities or the GitHub plugin settings to resolve the required permission before another write attempt."
    }

    private fun requiredGitHubPermission(action: String): String = when (action) {
        "create_issue", "update_issue" -> "Issues: write"
        "create_pull_request", "update_pull_request", "mark_ready_for_review", "submit_pull_request_review", "create_pull_request_review", "reply_pull_request_comment" -> "Pull requests: write"
        "create_issue_comment" -> "Issues: write or Pull requests: write"
        "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow" -> "Actions: write"
        else -> "Contents: write"
    }

    private fun compactGitRef(value: JsonObject): JsonObject = buildJsonObject {
        listOf("ref", "sha", "label").forEach { key -> value[key]?.let { put(key, it) } }
    }

    private fun requireToken() {
        require(apiToken.isNotBlank()) { "A GitHub token is required for repository write actions." }
    }

    private suspend fun writeGitHubApi(url: String, method: HttpMethod, body: JsonObject): HttpResponse = scheduler.execute(null) {
        check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
        rateLimits.requireAvailable("core")
        scheduler.recordRequest()
        httpClient.request(url) {
            this.method = method
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "GPT-Mobile-App")
            header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
            header(HttpHeaders.Authorization, "Bearer $apiToken")
            setBody(body.toString())
        }.also {
            rateLimits.record(it.headers, it.status.value)
            if (it.status.value == 401) authenticationRejected.set(true)
        }
    }

    private suspend fun getGitHubApi(url: String): HttpResponse = dev.chungjungsoo.gptmobile.data.github.GitHubReadRetry.run {
        scheduler.execute(null) {
            check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
            rateLimits.requireAvailable(if (url.contains("/search/")) "search" else "core")
            scheduler.recordRequest()
            httpClient.get(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GPT-Mobile-App")
                header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
                if (apiToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiToken")
            }.also {
                rateLimits.record(it.headers, it.status.value)
                if (it.status.value == 401) authenticationRejected.set(true)
                // Read the body within the retry boundary, including an interrupted HTTP/2 body.
                it.bodyAsText()
                if (dev.chungjungsoo.gptmobile.data.github.GitHubReadRetry.transientStatus(it.status.value)) {
                    throw dev.chungjungsoo.gptmobile.data.github.GitHubReadRetry.HttpFailure(it.status.value, it.headers["Retry-After"])
                }
            }
        }
    }

    private fun truncate(text: String, maxLength: Int): String = if (text.length <= maxLength) text else text.take(maxLength) + "\n...[truncated]"

    private fun successResult(callId: String, text: String): AgentToolResult =
        AgentToolResult(
            callId = callId,
            content = ToolResultContent.Text(text),
            isError = false
        )

    private fun errorResult(callId: String, errorMessage: String): AgentToolResult {
        AppLogRecorder.record(
            "GitHubTool",
            "Request failed · call=$callId · ${truncate(errorMessage.replace("\n", " "), 600)}",
            "E"
        )
        return AgentToolResult(
            callId = callId,
            content = ToolResultContent.Text(errorMessage),
            isError = true,
            errorCode = when {
                errorMessage.startsWith("GITHUB_AUTHENTICATION_REQUIRED") -> "AUTH_REQUIRED"
                errorMessage.startsWith("GITHUB_INVALID_ARGUMENT") -> "INVALID_ARGUMENTS"
                errorMessage.startsWith("GITHUB_RATE_LIMITED") -> "RATE_LIMITED"
                errorMessage.startsWith("GITHUB_SSO_REQUIRED") -> "GITHUB_SSO_REQUIRED"
                errorMessage.startsWith("GITHUB_BRANCH_RULES_BLOCKED") -> "GITHUB_BRANCH_RULES_BLOCKED"
                errorMessage.startsWith("GITHUB_WRITE_BLOCKED") -> "GITHUB_ENDPOINT_PERMISSION_REQUIRED"
                else -> null
            }
        )
    }
}
