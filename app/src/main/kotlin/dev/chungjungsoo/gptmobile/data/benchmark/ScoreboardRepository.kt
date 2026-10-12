package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class OnlineScoreboardState(
    val entries: List<ScoreboardEntry> = emptyList(),
    val loading: Boolean = false,
    val updatedAt: Long = 0,
    val cached: Boolean = false,
    val message: String? = null,
    val hasSnapshot: Boolean = false
)

data class ScoreboardSubmissionStatus(val state: State, val message: String) {
    enum class State { PENDING, ACCEPTED, REJECTED }
}

internal fun classifyScoreboardSubmission(state: String, comments: List<String>): ScoreboardSubmissionStatus {
    if (comments.any { it.startsWith("Accepted as a self-reported benchmark.") } && state == "closed") {
        return ScoreboardSubmissionStatus(ScoreboardSubmissionStatus.State.ACCEPTED, "Accepted and published to the online scoreboard.")
    }
    val rejection = comments.lastOrNull { it.startsWith("Scoreboard validation failed:") }
    if (rejection != null) {
        return ScoreboardSubmissionStatus(
            ScoreboardSubmissionStatus.State.REJECTED,
            rejection.substringAfter(':').trim().take(240)
        )
    }
    return ScoreboardSubmissionStatus(ScoreboardSubmissionStatus.State.PENDING, "Waiting for repository validation. This screen will check again shortly.")
}

@Singleton
class ScoreboardRepository @Inject constructor(
    @param:ApplicationContext context: Context,
    private val connections: ToolConnectionRepository,
    private val vault: SecretVault
) {
    private val preferences = context.getSharedPreferences("community_scoreboard_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OnlineScoreboardState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = preferences.getString("index", null)?.let { raw ->
                runCatching { ScoreboardFeedSnapshot(raw, ScoreboardFeed.decode(raw), preferences.getString("raw_etag", null)) }.getOrNull()
            }
            if (saved == null) preferences.edit().remove("index").remove("raw_etag").apply()
            if (mutableState.value.entries.isEmpty() && saved != null) {
                mutableState.value = OnlineScoreboardState(BenchmarkScoreboard.best(saved.index.entries), updatedAt = saved.index.updatedAt, cached = true, hasSnapshot = true)
            }
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val snapshot = ScoreboardFeed(client).fetch(saved)
                if (snapshot.index.entries.isEmpty() && !saved?.index?.entries.isNullOrEmpty()) {
                    mutableState.value = mutableState.value.copy(loading = false, cached = true, message = "The published feed is empty. Showing the last usable snapshot; dataset date is unchanged.")
                    AppLogRecorder.record("Scoreboard", "REFRESH_EMPTY · previousSnapshotRetained=true · incomingUpdatedAt=${snapshot.index.updatedAt}", "W")
                    return@withLock
                }
                val persisted = preferences.edit().putString("index", snapshot.raw).putString("raw_etag", snapshot.etag).remove("etag").commit()
                mutableState.value = OnlineScoreboardState(
                    BenchmarkScoreboard.best(snapshot.index.entries),
                    updatedAt = snapshot.index.updatedAt,
                    hasSnapshot = true,
                    message = when {
                        !persisted -> "Scores are current, but could not be saved for offline use."
                        snapshot.index.entries.isEmpty() -> "The public feed was reached successfully, but no validated remote-model results have been published yet."
                        else -> null
                    }
                )
                AppLogRecorder.record("Scoreboard", "REFRESH_OK · entries=${snapshot.index.entries.size} · updatedAt=${snapshot.index.updatedAt} · saved=$persisted")
            } catch (error: Exception) {
                if (error is CancellationException) {
                    mutableState.value = mutableState.value.copy(loading = false, cached = mutableState.value.hasSnapshot)
                    throw error
                }
                mutableState.value = mutableState.value.copy(loading = false, cached = mutableState.value.hasSnapshot, message = error.message?.take(240) ?: "Could not load the online scoreboard.")
                AppLogRecorder.record("Scoreboard", "REFRESH_FAILED · savedCopy=${mutableState.value.hasSnapshot} · ${mutableState.value.message}", "W")
            }
        }
    }

    suspend fun sharingAccounts(): List<ToolConnection> = connections.listConnections().filter {
        it.type == ToolConnectionType.GITHUB && it.toolPolicy != "READ_ONLY" && it.secretRef != null
    }

    /** Called only after an explicit Publish action with a selected connected account. */
    suspend fun submit(data: ScoreboardSubmission, connectionUid: String): Long = withContext(Dispatchers.IO) {
        require(BenchmarkScoreboard.evaluate(data) != null && data.issueNumber == null) { "Only a complete local remote-model result can be shared." }
        val account = checkNotNull(sharingAccounts().firstOrNull { it.connectionUid == connectionUid }) { "Choose a GitHub connection that permits writes in Settings → Tools." }
        val bytes = account.secretRef?.let { vault.read(it) }
        val token = try {
            bytes?.decodeToString()?.trim()
        } finally {
            bytes?.fill(0)
        }
        check(!token.isNullOrBlank()) { "Reconnect this GitHub account before sharing." }
        val body = "Community scoreboard submission. Self-reported; no prompts, responses, keys or endpoint URLs.\n\n```json\n${json.encodeToString(data)}\n```"
        require(body.toByteArray().size <= 60_000) { "This result is too large to share." }
        val response = client.post("https://api.github.com/repos/$REPOSITORY/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", "[Scoreboard v1] ${data.kind.name} ${data.plan.label} ${data.id}")
                    put("body", body)
                }.toString()
            )
        }
        check(response.status.value == 201) {
            when (response.status.value) {
                401, 403 -> "GitHub refused sharing. Reconnect with permission to create issues in $REPOSITORY."
                404 -> "The scoreboard repository is unavailable to this account."
                429 -> "GitHub is limiting submissions. Try again later."
                else -> "Could not submit scores (HTTP ${response.status.value}). Check GitHub before retrying."
            }
        }
        checkNotNull(json.parseToJsonElement(response.boundedMetadata(500_000)).jsonObject["number"]?.jsonPrimitive?.longOrNull) { "GitHub did not return a submission number." }
    }

    suspend fun submissionStatus(issue: Long, connectionUid: String): ScoreboardSubmissionStatus = withContext(Dispatchers.IO) {
        require(issue in 1..100_000_000) { "Invalid submission number." }
        val account = checkNotNull(sharingAccounts().firstOrNull { it.connectionUid == connectionUid }) { "Reconnect the GitHub account used to share this result." }
        val bytes = account.secretRef?.let { vault.read(it) }
        val token = try {
            bytes?.decodeToString()?.trim()
        } finally {
            bytes?.fill(0)
        }
        check(!token.isNullOrBlank()) { "Reconnect the GitHub account used to share this result." }
        suspend fun get(path: String) = client.get("https://api.github.com/repos/$REPOSITORY$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
        }
        val issueResponse = get("/issues/$issue")
        check(issueResponse.status.value == 200) { "Could not read submission status (HTTP ${issueResponse.status.value})." }
        val issueJson = json.parseToJsonElement(issueResponse.boundedMetadata(500_000)).jsonObject
        val issueState = issueJson["state"]?.jsonPrimitive?.content.orEmpty()
        val commentsResponse = get("/issues/$issue/comments?per_page=100")
        check(commentsResponse.status.value == 200) { "Could not read validation comments (HTTP ${commentsResponse.status.value})." }
        val comments = (json.parseToJsonElement(commentsResponse.boundedMetadata(500_000)) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonObject["body"]?.jsonPrimitive?.content }.orEmpty()
        classifyScoreboardSubmission(issueState, comments)
    }

    companion object {
        const val REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
        const val MAX_INDEX_BYTES = 8_000_000
        private val client by lazy {
            HttpClient(OkHttp) {
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 20_000
                }
            }
        }
    }
}
