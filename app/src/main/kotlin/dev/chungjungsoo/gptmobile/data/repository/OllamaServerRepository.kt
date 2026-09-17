package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.ollama.OllamaModelEntry
import dev.chungjungsoo.gptmobile.data.ollama.OllamaTagsResponse
import dev.chungjungsoo.gptmobile.data.ollama.OllamaVersionResponse
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class OllamaServerHealth(
    val isOnline: Boolean,
    val version: String? = null,
    val modelCount: Int = 0,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

@Singleton
class OllamaServerRepository @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var url = baseUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }

    suspend fun checkHealth(baseUrl: String): Result<OllamaServerHealth> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Server URL cannot be empty"))
        }

        val startTime = System.currentTimeMillis()
        runCatching {
            // First probe /api/version
            val versionUrl = "$normalized/api/version"
            val conn = (URL(versionUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
            }

            var detectedVersion: String? = null
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    runCatching {
                        val parsed = json.decodeFromString<OllamaVersionResponse>(body)
                        detectedVersion = parsed.version
                    }
                }
            } finally {
                conn.disconnect()
            }

            // Also check models count from /api/tags
            val tagsResult = fetchModels(baseUrl)
            val modelCount = tagsResult.getOrNull()?.size ?: 0
            val latency = System.currentTimeMillis() - startTime

            OllamaServerHealth(
                isOnline = true,
                version = detectedVersion,
                modelCount = modelCount,
                latencyMs = latency
            )
        }
    }

    suspend fun fetchModels(baseUrl: String): Result<List<OllamaModelEntry>> = withContext(Dispatchers.IO) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Server URL cannot be empty"))
        }

        runCatching {
            val endpoint = "$normalized/api/tags"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
            }

            try {
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IllegalStateException(
                        buildString {
                            append("Server returned HTTP ").append(responseCode)
                            if (!errorBody.isNullOrBlank()) append(": ").append(errorBody)
                        }
                    )
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<OllamaTagsResponse>(body)
                parsed.models.sortedBy { it.name.lowercase() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
