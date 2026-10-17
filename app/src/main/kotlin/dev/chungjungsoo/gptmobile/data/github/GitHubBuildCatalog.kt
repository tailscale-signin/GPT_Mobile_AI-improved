package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
enum class GitHubBuildSource { RELEASE, WORKFLOW }

/** Asset identifiers are used to construct authenticated API downloads; remote download URLs are never retained. */
@Serializable
data class GitHubApkBuild(
    val id: String,
    val owner: String,
    val repo: String,
    val source: GitHubBuildSource,
    val assetId: Long,
    val title: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val detailsUrl: String,
    val branch: String? = null,
    val commitSha: String? = null,
    val releaseTag: String? = null,
    val prerelease: Boolean = false,
    val expired: Boolean = false,
    val digest: String? = null,
    val runId: Long? = null
)

@Serializable
data class GitHubBuildPage(val builds: List<GitHubApkBuild>, val hasMore: Boolean)

/**
 * Read-only discovery for the workspace Builds tab. One page loads 30 releases or
 * 10 successful branch runs. Artifact enumeration and response bodies are bounded;
 * cancellation never becomes a normal failure and credentials never enter errors.
 */
class GitHubBuildCatalog(
    private val token: String,
    private val client: HttpClient = sharedClient
) {
    private val rateLimits = if (client === sharedClient) {
        GitHubSessionCaches.rateLimitsForCredential(token)
    } else {
        GitHubRateLimitManager()
    }

    suspend fun releases(owner: String, repo: String, page: Int = 1): GitHubBuildPage {
        val root = repositoryRoot(owner, repo)
        require(page in 1..MAX_PAGES) { "Choose a valid release page." }
        val response = get("$root/releases?per_page=$RELEASES_PER_PAGE&page=$page")
        val releases = response.value as? JsonArray ?: invalidResponse()
        val builds = releases.mapNotNull { it as? JsonObject }.filter {
            it.boolean("draft") != true && it.string("published_at").isNotBlank()
        }.flatMap { release ->
            val tag = release.string("tag_name")
            if (tag.isBlank()) return@flatMap emptyList()
            val title = release.string("name").ifBlank { tag }
            (release["assets"] as? JsonArray).orEmpty().mapNotNull asset@{ item ->
                val asset = item as? JsonObject ?: return@asset null
                val name = asset.string("name")
                val id = asset.positiveId("id") ?: return@asset null
                if (!name.endsWith(".apk", ignoreCase = true) || asset.string("state") != "uploaded") return@asset null
                GitHubApkBuild(
                    id = "release:$owner/$repo:$id",
                    owner = owner,
                    repo = repo,
                    source = GitHubBuildSource.RELEASE,
                    assetId = id,
                    title = title,
                    fileName = name,
                    sizeBytes = asset.long("size")?.coerceAtLeast(0) ?: 0,
                    createdAt = release.string("published_at"),
                    detailsUrl = "$WEB_ROOT/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}/releases/tag/${GitHubWorkspaceClient.segment(tag)}",
                    commitSha = release.string("target_commitish").takeIf(COMMIT_SHA::matches),
                    releaseTag = tag,
                    prerelease = release.boolean("prerelease") == true,
                    digest = asset.string("digest").takeIf { it.isNotBlank() }
                )
            }
        }
        return GitHubBuildPage(
            builds = newestFirst(builds),
            hasMore = page < MAX_PAGES && (response.hasNext ?: (releases.size == RELEASES_PER_PAGE))
        )
    }

    suspend fun workflowBuilds(owner: String, repo: String, branch: String, page: Int = 1): GitHubBuildPage {
        val root = repositoryRoot(owner, repo)
        require(branch.isNotBlank() && branch.length <= 512 && branch.none(Char::isISOControl)) { "Choose a valid branch for workflow builds." }
        require(page in 1..MAX_WORKFLOW_PAGES) { "Choose a valid workflow page." }
        val response = get("$root/actions/runs?branch=${GitHubWorkspaceClient.segment(branch)}&status=success&per_page=$RUNS_PER_PAGE&page=$page")
        val result = response.value as? JsonObject ?: invalidResponse()
        val runs = result["workflow_runs"] as? JsonArray ?: invalidResponse()
        // Verify these fields even though the API is filtered; only this selected branch is shown.
        val successfulRuns = runs.mapNotNull { it as? JsonObject }.filter {
            it.string("status") == "completed" &&
                it.string("conclusion") == "success" &&
                it.string("head_branch") == branch &&
                it.positiveId("id") != null
        }.take(RUNS_PER_PAGE)
        val permits = Semaphore(3)
        val builds = coroutineScope {
            successfulRuns.map { run ->
                async {
                    permits.withPermit { artifactsForRun(owner, repo, root, branch, run) }
                }
            }.awaitAll().flatten()
        }
        val moreByCount = result.long("total_count")?.let { page.toLong() * RUNS_PER_PAGE < it }
        return GitHubBuildPage(
            builds = newestFirst(builds),
            hasMore = page < MAX_WORKFLOW_PAGES &&
                runs.isNotEmpty() &&
                (response.hasNext ?: moreByCount ?: (runs.size == RUNS_PER_PAGE))
        )
    }

    private suspend fun artifactsForRun(owner: String, repo: String, root: String, branch: String, run: JsonObject): List<GitHubApkBuild> {
        val runId = run.positiveId("id") ?: return emptyList()
        val builds = mutableListOf<GitHubApkBuild>()
        for (page in 1..MAX_ARTIFACT_PAGES) {
            val response = get("$root/actions/runs/$runId/artifacts?per_page=$ARTIFACTS_PER_PAGE&page=$page")
            val result = response.value as? JsonObject ?: invalidResponse()
            val artifacts = result["artifacts"] as? JsonArray ?: invalidResponse()
            artifacts.mapNotNull { it as? JsonObject }.forEach { artifact ->
                val id = artifact.positiveId("id") ?: return@forEach
                val name = artifact.string("name")
                if (!likelyApkArtifact(name)) return@forEach
                builds += GitHubApkBuild(
                    id = "workflow:$owner/$repo:$id",
                    owner = owner,
                    repo = repo,
                    source = GitHubBuildSource.WORKFLOW,
                    assetId = id,
                    title = run.string("display_title").ifBlank { run.string("name") }.ifBlank { "Workflow build #$runId" },
                    fileName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip",
                    sizeBytes = artifact.long("size_in_bytes")?.coerceAtLeast(0) ?: 0,
                    createdAt = artifact.string("created_at").ifBlank { run.string("created_at") },
                    detailsUrl = "$WEB_ROOT/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}/actions/runs/$runId",
                    branch = branch,
                    commitSha = run.string("head_sha").takeIf(COMMIT_SHA::matches),
                    expired = artifact.boolean("expired") == true,
                    digest = artifact.string("digest").takeIf { it.isNotBlank() },
                    runId = runId
                )
            }
            val more = response.hasNext ?: result.long("total_count")?.let { page.toLong() * ARTIFACTS_PER_PAGE < it }
                ?: (artifacts.size == ARTIFACTS_PER_PAGE)
            if (!more) return builds
        }
        throw CatalogFailure("A workflow run has too many artifacts to list here. Open the run on GitHub to inspect its builds.")
    }

    private suspend fun get(path: String): ApiPage {
        try {
            try {
                rateLimits.requireAvailable("core")
            } catch (_: IllegalStateException) {
                throw CatalogFailure("GitHub is limiting requests. Wait for its limit to reset, then refresh the builds.")
            }
            return client.prepareGet("$API_ROOT$path") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GPT-Mobile-App")
                header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
                if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            }.execute { response ->
                rateLimits.record(response.headers)
                if (!response.status.isSuccess()) {
                    response.bodyAsChannel().cancel(null)
                    val rateLimited = response.status.value == 429 ||
                        response.status.value == 403 &&
                        (response.headers["X-RateLimit-Remaining"] == "0" || response.headers["Retry-After"] != null)
                    throw CatalogFailure(
                        when {
                            rateLimited -> "GitHub is limiting requests. Wait for its limit to reset, then refresh the builds."
                            response.status.value == 401 -> "Reconnect this GitHub account to load its builds."
                            response.status.value == 403 -> "GitHub denied build access. Check repository access and Actions read permission for workflow builds."
                            response.status.value == 404 -> "GitHub could not find these builds or this account cannot access them."
                            else -> "GitHub could not load builds (HTTP ${response.status.value}). Try again."
                        }
                    )
                }
                val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                try {
                    if (declaredSize != null && declaredSize > MAX_METADATA_BYTES) throw CatalogFailure("GitHub build metadata is too large to display.")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (output.size() <= MAX_METADATA_BYTES) {
                        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_METADATA_BYTES + 1 - output.size()))
                        if (count < 0) break
                        if (count == 0) {
                            yield()
                        } else {
                            output.write(buffer, 0, count)
                        }
                    }
                    if (output.size() > MAX_METADATA_BYTES) throw CatalogFailure("GitHub build metadata is too large to display.")
                } finally {
                    channel.cancel(null)
                }
                ApiPage(
                    value = Json.parseToJsonElement(output.toByteArray().decodeToString(throwOnInvalidSequence = true)),
                    // Pagination URLs never become request targets; later calls construct their own page path.
                    hasNext = response.headers[HttpHeaders.Link]?.let { NEXT_LINK.containsMatchIn(it) }
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: CatalogFailure) {
            throw failure
        } catch (_: Exception) {
            throw CatalogFailure("Could not load GitHub builds. Check the connection and try again.")
        }
    }

    private data class ApiPage(val value: JsonElement, val hasNext: Boolean?)
    private class CatalogFailure(message: String) : IllegalStateException(message)

    companion object {
        private const val API_ROOT = "https://api.github.com"
        private const val WEB_ROOT = "https://github.com"
        private const val RELEASES_PER_PAGE = 30
        private const val RUNS_PER_PAGE = 10
        private const val ARTIFACTS_PER_PAGE = 100
        private const val MAX_ARTIFACT_PAGES = 3
        private const val MAX_PAGES = 1000

        // GitHub limits filtered workflow searches to 1,000 runs.
        private const val MAX_WORKFLOW_PAGES = 100
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private val REPOSITORY_PART = Regex("[A-Za-z0-9_.-]+")
        private val COMMIT_SHA = Regex("[a-fA-F0-9]{40}")
        private val NEXT_LINK = Regex("rel\\s*=\\s*\"?next(?:\"|[;\\s]|$)")
        private val APK_TOKEN = Regex("(?:^|[-_. ])apk(?:$|[-_. ])")
        private val NON_BUILD_TOKEN = Regex("(?:^|[-_. ])(?:report|reports|coverage|log|logs|symbols|mapping|idsig|aab|provenance)(?:$|[-_. ])")
        private val APP_BUILD = Regex("^(?:app|android)[-_.](?:.*[-_.])?(?:debug|release)(?:[-_.].*)?$")
        private val sharedClient by lazy {
            HttpClient(OkHttp) {
                followRedirects = false
                install(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsHttpPlugin)
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
            }
        }

        private fun repositoryRoot(owner: String, repo: String): String {
            require(
                owner.length in 1..100 &&
                    repo.length in 1..100 &&
                    REPOSITORY_PART.matches(owner) &&
                    REPOSITORY_PART.matches(repo) &&
                    owner !in setOf(".", "..") &&
                    repo !in setOf(".", "..")
            ) {
                "Choose a valid GitHub repository."
            }
            return "/repos/${GitHubWorkspaceClient.segment(owner)}/${GitHubWorkspaceClient.segment(repo)}"
        }

        private fun likelyApkArtifact(name: String): Boolean {
            val normalized = name.lowercase(Locale.ROOT).removeSuffix(".zip")
            if (normalized.isBlank() || NON_BUILD_TOKEN.containsMatchIn(normalized)) return false
            return normalized.endsWith(".apk") ||
                APK_TOKEN.containsMatchIn(normalized) ||
                APP_BUILD.matches(normalized) ||
                normalized.startsWith("signed-release-")
        }

        private fun newestFirst(builds: List<GitHubApkBuild>): List<GitHubApkBuild> = builds.distinctBy { it.id }
            .sortedWith(compareByDescending<GitHubApkBuild> { it.createdAt }.thenBy { it.fileName.lowercase(Locale.ROOT) })

        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.positiveId(key: String): Long? = long(key)?.takeIf { it > 0 }
        private fun invalidResponse(): Nothing = throw CatalogFailure("GitHub returned incomplete build metadata. Refresh and try again.")
    }
}
