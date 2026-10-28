package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class EngineeringApiException(val status: Int, val retryAtEpochMs: Long?) : IOException("GitHub API unavailable (HTTP $status)")

/** Fixed host, no redirects, bounded responses, and a fresh credential for every request. */
internal open class GitHubEngineeringApi(private val token: suspend () -> String?) {
    internal var expectedWriteActor: String? = null
    open suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.startsWith("//") && '\r' !in path && '\n' !in path)
        val credential = requireNotNull(token()?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }) { "Reconnect the GitHub account" }
        if (method != "GET") {
            val expectedActor = requireNotNull(expectedWriteActor) { "Engineering write has no approved account" }
            val actualActor = JSONObject(GitHubEngineeringApi { credential }.request("/user")).getLong("id").toString()
            check(actualActor == expectedActor) { "GitHub credential changed accounts; review approval again" }
        }
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
            ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(JSON_MEDIA) else null
        val request = Request.Builder()
            .url("https://api.github.com$path")
            .header("Authorization", "Bearer $credential")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .method(method, requestBody)
            .build()
        transport.newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                val retry = engineeringRetryDeadline(
                    response.code,
                    response.header("Retry-After"),
                    response.header("X-RateLimit-Reset"),
                    response.header("X-RateLimit-Remaining"),
                    System.currentTimeMillis()
                )
                throw EngineeringApiException(response.code, retry)
            }
            val output = java.io.ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(8192)
                while (output.size() <= 4 * 1024 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            check(output.size() <= 4 * 1024 * 1024) { "GitHub response exceeds inspection budget" }
            output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        // A lost response is an uncertain remote outcome, never permission to
        // replay an engineering mutation inside the HTTP transport.
        private val transport = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun root(owner: String, repo: String): String {
            require(owner.matches(Regex("[A-Za-z0-9-]+")) && repo.matches(Regex("[A-Za-z0-9._-]+")) && repo !in setOf(".", ".."))
            return "/repos/$owner/$repo"
        }
    }
}

/** Injectable transport construction also permits end-to-end controller tests without credentials. */
open class GitHubEngineeringApiFactory @javax.inject.Inject constructor() {
    internal open fun create(token: suspend () -> String?): GitHubEngineeringApi = GitHubEngineeringApi(token)
}
