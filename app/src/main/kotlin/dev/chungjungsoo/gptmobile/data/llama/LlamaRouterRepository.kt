package dev.chungjungsoo.gptmobile.data.llama

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of querying a Llama router or discovery endpoint.
 */
sealed class LlamaRouterResult {
    data class Success(val models: List<LlamaModelInfo>) : LlamaRouterResult()
    data class Error(val message: String, val cause: Throwable? = null) : LlamaRouterResult()
}

/**
 * Repository to discover and fetch available models from a Llama server / router endpoint.
 */
@Singleton
class LlamaRouterRepository @Inject constructor(
    private val client: HttpClient
) {
    /**
     * Fetches models from the given Llama base URL and endpoint path.
     * Supports both OpenAI-compatible `/v1/models` and native llama.cpp endpoints.
     */
    suspend fun fetchRouterModels(
        baseUrl: String,
        endpointPath: String = "/v1/models"
    ): LlamaRouterResult = withContext(Dispatchers.IO) {
        val trimmedBase = baseUrl.trim().removeSuffix("/")
        if (trimmedBase.isBlank()) {
            return@withContext LlamaRouterResult.Error("Base URL cannot be blank")
        }

        val normalizedEndpoint = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
        val fullUrl = "$trimmedBase$normalizedEndpoint"

        try {
            val response = client.get { url(fullUrl) }
            val text = response.bodyAsText()

            if (text.isBlank()) {
                return@withContext LlamaRouterResult.Error("Empty response from Llama router endpoint")
            }

            val root = JSONObject(text)
            val dataArray = root.optJSONArray("data")
            val models = mutableListOf<LlamaModelInfo>()

            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val name = obj.optString("name", id)
                    val contextLength = obj.optInt("context_length", 4096)
                    if (id.isNotBlank()) {
                        val option = LlamaRouterModelOption(id = id, name = name, contextLength = contextLength)
                        models.add(LlamaModelMapper.fromRouterModel(option))
                    }
                }
            } else if (root.has("models")) {
                // Alternative format: { "models": [...] }
                val array = root.getJSONArray("models")
                for (i in 0 until array.length()) {
                    val item = array.get(i)
                    val id = if (item is JSONObject) item.optString("name", item.optString("id", "")) else item.toString()
                    if (id.isNotBlank()) {
                        val option = LlamaRouterModelOption(id = id, name = id)
                        models.add(LlamaModelMapper.fromRouterModel(option))
                    }
                }
            }

            LlamaRouterResult.Success(models)
        } catch (e: Exception) {
            LlamaRouterResult.Error("Failed to connect to Llama router: ${e.message}", e)
        }
    }
}
