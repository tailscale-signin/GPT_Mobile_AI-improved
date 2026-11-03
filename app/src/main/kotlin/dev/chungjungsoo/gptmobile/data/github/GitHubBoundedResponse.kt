package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.yield

internal class GitHubResponseTooLarge : IllegalStateException("GITHUB_RESPONSE_TOO_LARGE: response exceeds the 4 MiB inspection limit. Request a smaller page, a file range, or a non-recursive tree. A write response requires reconciliation before retry.")

internal data class GitHubBoundedResponse(val status: HttpStatusCode, val headers: Headers, private val text: String) {
    fun bodyAsText(): String = text
}

/** The streaming statement prevents Ktor from buffering an unbounded body before our limit. */
internal suspend fun HttpClient.githubRequest(url: String, block: HttpRequestBuilder.() -> Unit): GitHubBoundedResponse =
    prepareRequest(url, block).execute { response ->
        val limit = 4 * 1024 * 1024
        if ((response.headers["Content-Length"]?.toLongOrNull() ?: 0) > limit) throw GitHubResponseTooLarge()
        val output = ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        try {
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = channel.readAvailable(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0) yield() else output.write(buffer, 0, count)
            }
            if (output.size() > limit) throw GitHubResponseTooLarge()
            GitHubBoundedResponse(response.status, response.headers, output.toByteArray().decodeToString())
        } finally {
            channel.cancel(null)
        }
    }
