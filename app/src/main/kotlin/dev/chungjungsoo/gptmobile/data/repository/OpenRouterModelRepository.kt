package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.model.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.model.OpenRouterModelsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenRouterModelRepository @Inject constructor(
    private val httpClient: HttpClient
) {
    @Volatile
    private var cachedModels: List<OpenRouterModelItem>? = null

    suspend fun fetchModels(forceRefresh: Boolean = false): Result<List<OpenRouterModelItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedModels != null) {
            return@withContext Result.success(cachedModels!!)
        }
        try {
            val response: OpenRouterModelsResponse = httpClient.get("https://openrouter.ai/api/v1/models") {
                header("HTTP-Referer", "https://github.com/tailscale-signin/GPT_Mobile_AI-improved")
                header("X-Title", "GPT Mobile")
            }.body()
            cachedModels = response.data
            Result.success(response.data)
        } catch (e: Exception) {
            cachedModels?.let {
                return@withContext Result.success(it)
            }
            Result.failure(e)
        }
    }

    fun getCachedModels(): List<OpenRouterModelItem> {
        return cachedModels ?: emptyList()
    }
}
