package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlamaBatchClient(
    private val serverUrl: String = "http://localhost:8080",
    private val apiKey: String? = null,
    private val batchSize: Int = 50
) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30000, TimeUnit.MILLISECONDS)
        .readTimeout(30000, TimeUnit.MILLISECONDS)
        .writeTimeout(30000, TimeUnit.MILLISECONDS)
        .build()
    
    suspend fun processBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val jsonlContent = requests.map { request ->
            """{"prompt": "${escapeJson(request.prompt)}", "context": "", "n_tokens_to_predict": ${request.maxTokens}}"""
        }.joinToString("\n")
        
        try {
            val response = batchProcess(jsonlContent)
            val results = parseLlamaResponse(response, requests.size)
            onComplete(results)
        } catch (e: Exception) {
            // Fallback to individual requests if batch fails
            processOpenRouterFallback(requests, onComplete)
        }
    }
    
    private suspend fun batchProcess(jsonlContent: String): String {
        val requestBody = jsonlContent.toRequestBody(
            "application/x-ndjson".toMediaTypeOrNull()
        )
        
        val requestObj = Request.Builder()
            .url("$serverUrl/v1/batch")
            .post(requestBody)
            .addHeader("Content-Type", "application/x-ndjson")
            .apply {
                apiKey?.let { addHeader("Authorization", "Bearer $it") }
            }
            .build()
        
        val response = client.newCall(requestObj).execute()
        
        if (!response.isSuccessful) {
            throw Exception("Llama.cpp batch API error: ${response.code} - ${response.body?.string()}")
        }
        
        return response.body?.string() ?: ""
    }
    
    private fun parseLlamaResponse(response: String, totalRequests: Int): List<BatchResult> {
        val results = mutableListOf<BatchResult>()
        val lines = response.split("\n").filter { it.isNotBlank() }
        
        for ((index, line) in lines.withIndex()) {
            try {
                val tokenIds = org.json.JSONArray(line).getJSONArray("token_ids")
                val text = tokenIds.joinToString("") { id ->
                    // Convert token ID to character (simplified - real implementation needs tokenizer)
                    Character.toString(id.code)
                }
                results.add(BatchResult.Success(index, text))
            } catch (e: Exception) {
                results.add(BatchResult.Failure(index, e.message ?: "Parse error"))
            }
        }
        
        // Pad with failures if response has fewer lines than requests
        while (results.size < totalRequests) {
            val index = results.size
            results.add(BatchResult.Failure(index, "No response for request $index"))
        }
        
        return results
    }
    
    private suspend fun processOpenRouterFallback(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) {
        // Reuse OpenRouter client logic as fallback
        val openRouterClient = OpenRouterBatchClient(apiKey ?: "", 10, 1000, 30000)
        openRouterClient.processBatch(requests, onComplete)
    }
    
    private fun escapeJson(input: String): String {
        return input.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t")
    }
}
