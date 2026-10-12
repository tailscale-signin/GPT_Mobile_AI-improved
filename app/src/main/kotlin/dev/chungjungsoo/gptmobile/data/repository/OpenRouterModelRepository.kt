package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelsResponse
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
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

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var cachedAt = 0L
    private var failedAt = Long.MIN_VALUE

    suspend fun fetchModels(forceRefresh: Boolean = false): Result<List<OpenRouterModelItem>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            cachedModels?.takeIf { !forceRefresh && now - cachedAt in 0..3_600_000 }?.let {
                return@withLock Result.success(it)
            }

            if (!forceRefresh && failedAt != Long.MIN_VALUE && now - failedAt in 0..30_000) {
                return@withLock Result.failure(IllegalStateException("OpenRouter catalog is cooling down after a failed refresh."))
            }
            runCatching {
                val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "GPTMobile/1.0")
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IllegalStateException("Failed to fetch OpenRouter models: HTTP $responseCode")
                    }

                    val responseBody = connection.inputStream.use { dev.chungjungsoo.gptmobile.data.network.BoundedResponseText.read(it, 8 * 1024 * 1024) }
                    val sorted = json.decodeFromString<OpenRouterModelsResponse>(responseBody)
                        .data
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.id })
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    cachedModels = sorted
                    cachedAt = System.nanoTime() / 1_000_000
                    failedAt = Long.MIN_VALUE
                    dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.remember(sorted)
                    sorted
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                failedAt = System.nanoTime() / 1_000_000
            }
        }
    }

    private companion object {
        const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    }
}
