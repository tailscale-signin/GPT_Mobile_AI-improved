package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsResponse
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterAccountClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Legacy callers use an isolated one-shot management session. The workspace owns all normal key data. */
@Singleton
class OpenRouterCreditsRepository @Inject constructor() {
    suspend fun fetchCredits(apiKey: String, forceRefresh: Boolean = false, cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS): Result<OpenRouterCreditsData> = try {
        val client = OpenRouterAccountClient()
        val endpoint = "https://openrouter.ai/api/v1"
        check(client.key(endpoint, apiKey).isManagement) { "Account-wide balance requires separate management access." }
        val data = Json { ignoreUnknownKeys = true }.decodeFromString<OpenRouterCreditsResponse>(client.get(endpoint, apiKey, "credits")).data
        Result.success(checkNotNull(data) { "Account-wide balance unavailable" })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
    fun clearCache() = Unit
    companion object {
        const val CREDITS_URL = "https://openrouter.ai/api/v1/credits"
        const val DEFAULT_CACHE_TTL_MS = 300_000L
    }
}
