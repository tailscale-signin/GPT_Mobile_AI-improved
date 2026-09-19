package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterBatchClient(
    private val apiKey: String,
    private val maxConcurrentRequests: Int = 10,
    private val retryDelayMs: Long = 1000,
    private val timeoutMs: Long = 30000
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun processBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val results = mutableListOf<BatchResult>()

        // Process in parallel with concurrency limit using semaphore
        val semaphore = Semaphore(maxConcurrentRequests)

        for ((index, request) in requests.withIndex()) {
            semaphore.withPermit {
                try {
                    val response = processSingleRequest(request)
                    results.add(BatchResult.Success(index, response))
                } catch (e: Exception) {
                    results.add(BatchResult.Failure(index, e.message ?: "Unknown error"))
                }
            }
        }

        onComplete(results)
    }

    suspend fun processRequest(request: BatchRequest): String {
        return processSingleRequest(request)
    }

    private fun processSingleRequest(request: BatchRequest): String {
        val messages = listOf(
            JSONObject().put("role", "user").put("content", request.prompt)
        )

        val body = JSONObject()
            .put("model", "meta-llama/Meta-Llama-3.1-8B-Instruct")
            .put("messages", org.json.JSONArray(messages))
            .put("temperature", request.temperature.toDouble())
            .put("max_tokens", request.maxTokens)

        val requestBody = body.toString().toRequestBody(
            "application/json".toMediaTypeOrNull()
        )

        val requestObj = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(requestObj).execute()

        if (!response.isSuccessful) {
            throw Exception("OpenRouter API error: ${response.code} - ${response.body?.string()}")
        }

        return response.body?.string() ?: ""
    }
}
