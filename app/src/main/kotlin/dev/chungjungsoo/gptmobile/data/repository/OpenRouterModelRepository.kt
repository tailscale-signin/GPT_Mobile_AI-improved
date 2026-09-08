package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelsResponse
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class OpenRouterModelRepository @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedModels: List<OpenRouterModelItem>? = null

    suspend fun fetchModels(forceRefresh: Boolean = false): Result<List<OpenRouterModelItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedModels != null) {
            return@withContext Result.success(cachedModels!!)
        }

        runCatching {
            val url = URL("https://openrouter.ai/api/v1/models")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GPTMobile/1.0")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Failed to fetch OpenRouter models: HTTP $responseCode")
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = json.decodeFromString<OpenRouterModelsResponse>(responseBody)
            val sorted = parsed.data.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.id }
            )
            cachedModels = sorted
            sorted
        }
    }
}
