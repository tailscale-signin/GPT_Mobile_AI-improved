package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.yield

internal class GitHubCiLogReader(private val client: HttpClient, private val token: String, private val scheduler: GitHubRequestScheduler, private val cache: GitHubResponseCache, private val requestPriority: Int = 0, private val authenticationRejected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean()) {
    data class Log(val text: String, val bytes: Int, val complete: Boolean)

    suspend fun read(path: String, immutable: Boolean): Log {
        check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
        if (immutable) {
            cache.getDecodedBlob("ci-log:$path")?.let {
                scheduler.recordCacheHit()
                return Log(it, it.encodeToByteArray().size, true)
            }
        }
        return scheduler.execute(if (immutable) "ci-log:$path" else null, priority = requestPriority) {
            check(!authenticationRejected.get()) { "GitHub authentication was rejected (HTTP 401). Reconnect the GitHub account before retrying any operation." }
            val network = client.config { followRedirects = false }
            try {
                var url = "https://api.github.com$path"
                val seen = mutableSetOf<String>()
                for (hop in 0..5) {
                    require(seen.add(url)) { "CI log redirect loop." }
                    val target = URI(url)
                    require(target.scheme == "https" && target.userInfo == null && target.host != null && (target.port == -1 || target.port == 443)) { "Unsafe CI log redirect." }
                    val host = target.host.lowercase()
                    require(host == "api.github.com" || host.endsWith(".githubusercontent.com") || host.endsWith(".blob.core.windows.net") || host.endsWith(".amazonaws.com")) { "CI log redirect is outside GitHub artifact storage." }
                    scheduler.recordRequest()
                    val response = network.get(url) {
                        header(HttpHeaders.UserAgent, "GPT-Mobile-App")
                        if (host == "api.github.com") {
                            header(HttpHeaders.Accept, "application/vnd.github+json")
                            header("X-GitHub-Api-Version", GitHubWorkspaceClient.API_VERSION)
                            if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    }
                    val channel = response.bodyAsChannel()
                    try {
                        if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                            val location = response.headers[HttpHeaders.Location] ?: error("CI log redirect has no location.")
                            url = target.resolve(location).toASCIIString()
                            continue
                        }
                        if (host == "api.github.com" && response.status.value == 401) {
                            authenticationRejected.set(true)
                            cache.clear()
                        }
                        check(response.status.value in 200..299) { "GitHub CI log HTTP ${response.status.value}." }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var eof = false
                        while (output.size() <= MAX_LOG_BYTES) {
                            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, MAX_LOG_BYTES + 1 - output.size()))
                            if (read < 0) {
                                eof = true
                                break
                            }
                            if (read == 0) {
                                yield()
                                continue
                            }
                            output.write(buffer, 0, read)
                            scheduler.recordBytes(read.toLong())
                            if (output.size() > MAX_LOG_BYTES) break
                        }
                        val bytes = output.toByteArray()
                        val complete = eof && bytes.size <= MAX_LOG_BYTES
                        val text = bytes.copyOf(minOf(bytes.size, MAX_LOG_BYTES)).decodeToString()
                        if (immutable && complete) cache.putDecodedBlob("ci-log:$path", text)
                        return@execute Log(text, minOf(bytes.size, MAX_LOG_BYTES), complete)
                    } finally {
                        channel.cancel(null)
                    }
                }
                error("Too many CI log redirects.")
            } finally {
                network.close()
            }
        }
    }

    companion object {
        const val MAX_LOG_BYTES = 4 * 1024 * 1024
    }
}
