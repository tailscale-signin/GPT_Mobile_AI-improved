package dev.chungjungsoo.gptmobile.data.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for communicating with Llama server in Router Mode to dynamically discover models.
 */
@Singleton
class LlamaRouterClient @Inject constructor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    /**
     * Query Llama router endpoint (e.g. /v1/models) and return parsed [LlamaModelInfo] list.
     */
    suspend fun fetchModels(baseUrl: String, endpointPath: String = "/v1/models"): List<LlamaModelInfo> =
        withContext(Dispatchers.IO) {
            val cleanUrl = baseUrl.trimEnd('/') + if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
            val request = Request.Builder()
                .url(cleanUrl)
                .get()
                .build()

            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val dataArray = json.optJSONArray("data") ?: return@withContext emptyList()

                val result = mutableListOf<LlamaModelInfo>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val name = obj.optString("name", id)
                    val contextLength = if (obj.has("context_length")) obj.getInt("context_length") else null
                    if (id.isNotBlank()) {
                        val option = LlamaRouterModelOption(id = id, name = name, contextLength = contextLength)
                        result.add(LlamaModelMapper.fromRouterModel(option))
                    }
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }
}
