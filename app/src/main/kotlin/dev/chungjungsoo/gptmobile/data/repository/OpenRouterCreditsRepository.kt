package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsResponse
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class CachedCredits(
    val data: OpenRouterCreditsData,
    val fetchedAtEpochMs: Long
)

@Singleton
class OpenRouterCreditsRepository @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cacheLock = Any()
    private val creditsCache = mutableMapOf<Int, CachedCredits>()

    suspend fun fetchCredits(
        apiKey: String,
        forceRefresh: Boolean = false,
        cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
    ): Result<OpenRouterCreditsData> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("OpenRouter API key is required"))
        }

        val cacheKey = trimmedKey.hashCode()
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            synchronized(cacheLock) {
                val cached = creditsCache[cacheKey]
                if (cached != null && (now - cached.fetchedAtEpochMs) < cacheTtlMs) {
                    return@withContext Result.success(cached.data)
                }
            }
        }

        runCatching {
            val connection = (URL(CREDITS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $trimmedKey")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode == 403) {
                    throw IllegalStateException("Management key required - regular API keys cannot fetch credits")
                }
                if (responseCode !in 200..299) {
                    val errorBody = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?.takeIf { it.isNotBlank() }
                    throw IllegalStateException(
                        buildString {
                            append("Failed to fetch OpenRouter credits: HTTP ")
                            append(responseCode)
                            if (errorBody != null) append(" - ").append(errorBody)
                        }
                    )
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString<OpenRouterCreditsResponse>(responseBody)
                val creditsData = parsed.data ?: throw IllegalStateException("Empty credits data received")

                synchronized(cacheLock) {
                    creditsCache[cacheKey] = CachedCredits(creditsData, System.currentTimeMillis())
                }

                creditsData
            } finally {
                connection.disconnect()
            }
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            creditsCache.clear()
        }
    }

    companion object {
        const val CREDITS_URL = "https://openrouter.ai/api/v1/credits"
        const val DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
