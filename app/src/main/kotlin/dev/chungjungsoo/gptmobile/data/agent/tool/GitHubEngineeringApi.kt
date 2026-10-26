package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val connection = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $credential")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) {
                val now = System.currentTimeMillis()
                val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 86_400)?.let { now + it * 1000 }
                    ?: connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let { it * 1000 }
                throw EngineeringApiException(connection.responseCode, retry)
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 4 * 1024 * 1024) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 4 * 1024 * 1024) { "GitHub response exceeds inspection budget" }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
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
