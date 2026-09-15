package dev.chungjungsoo.gptmobile.data.openrouter

import android.content.Context
import android.util.Log
import dev.chungjungsoo.gptmobile.data.repository.SecretRepository
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for fetching OpenRouter credits using the management key
 */
@Singleton
class OpenRouterCreditsService @Inject constructor(
    private val context: Context,
    private val secretRepository: SecretRepository
) {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedCredits: OpenRouterCreditsData? = null

    /**
     * Fetch credits from OpenRouter API using management key
     *
     * @param forceRefresh If true, bypass cache and fetch from API
     * @return Result containing credits data
     */
    suspend fun fetchCredits(forceRefresh: Boolean = false): Result<OpenRouterCreditsData> =
        withContext(Dispatchers.IO) {
            cachedCredits?.takeIf { !forceRefresh }?.let {
                return@withContext Result.success(it)
            }

            runCatching {
                val apiKey = secretRepository.getSecret("openrouter_management_key")
                    ?: throw IllegalStateException("OpenRouter management key not configured")

                val connection = (URL(CREDITS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "GPTMobile/1.0")
                }

                try {
                    val responseCode = connection.responseCode
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
                    val creditsResponse = json.decodeFromString<OpenRouterCreditsResponse>(responseBody)
                    cachedCredits = creditsResponse.data
                        ?: throw IllegalStateException("Empty credits response from OpenRouter")
                    Result.success(cachedCredits!!)
                } finally {
                    connection.disconnect()
                }
            }
        }

    companion object {
        const val CREDITS_URL = "https://openrouter.ai/api/v1/credits"
    }
}
