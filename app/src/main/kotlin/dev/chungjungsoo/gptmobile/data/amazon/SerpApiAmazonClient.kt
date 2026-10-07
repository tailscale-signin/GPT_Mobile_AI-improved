package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class AmazonProviderException(message: String) : Exception(message)

/** Fixed endpoint: neither prompts nor imported connection URLs can redirect credentials. */
class SerpApiAmazonClient private constructor(private val clientProvider: () -> HttpClient, private val apiKey: String) {
    constructor(networkClient: NetworkClient, apiKey: String) : this({ networkClient() }, apiKey)
    internal constructor(client: HttpClient, apiKey: String) : this({ client }, apiKey)

    suspend fun fetch(parameters: Map<String, String>): JsonObject {
        if (apiKey.isBlank()) throw AmazonProviderException("Add a SerpApi API key in Settings → Tool Connections → Amazon Search.")
        try {
            // A scoped client shares the engine but refuses credential-bearing redirects.
            val client = clientProvider().config { followRedirects = false }
            try {
                val response = client.get(ENDPOINT) {
                    timeout { requestTimeoutMillis = 40_000 }
                    parameter("api_key", apiKey.trim())
                    parameters.forEach { (key, value) -> parameter(key, value) }
                }
                if (response.status.value !in 200..299) {
                    response.bodyAsChannel().cancel(null)
                    throw AmazonProviderException(
                        when (response.status.value) {
                            401, 403 -> "SerpApi authentication or access failed. Check the Amazon Search connection's API key."
                            402 -> "SerpApi credits are exhausted. Check the provider account."
                            429 -> "SerpApi rate limit reached. Wait before retrying or check the provider allowance."
                            else -> "Amazon provider request failed (HTTP ${response.status.value})."
                        }
                    )
                }
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (output.size() + count > MAX_BODY_BYTES) throw AmazonProviderException("Amazon provider response exceeded the size limit.")
                        output.write(buffer, 0, count)
                    }
                } finally {
                    channel.cancel(null)
                }
                return Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())) as? JsonObject
                    ?: throw AmazonProviderException("Amazon provider returned an unsupported response.")
            } finally {
                client.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AmazonProviderException) {
            throw failure
        } catch (_: Exception) {
            // Raw exceptions and provider bodies can contain the API key or request URL.
            throw AmazonProviderException("Amazon provider request failed. Check connectivity and the SerpApi connection.")
        }
    }

    companion object {
        const val ENDPOINT = "https://serpapi.com/search"
        private const val MAX_BODY_BYTES = 1_500_000
    }
}
