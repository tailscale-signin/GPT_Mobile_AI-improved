package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.network.awaitBoundedText
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import dev.chungjungsoo.gptmobile.domain.service.executeBoundedBatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** llama-server batches work through bounded /completion calls, not an invented /v1/batch route. */
class LlamaBatchClient(private val serverUrl: String = "http://localhost:8080", private val apiKey: String? = null, private val batchSize: Int = 50) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS).connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()

    suspend fun processBatch(requests: List<BatchRequest>, onComplete: (List<BatchResult>) -> Unit) = withContext(Dispatchers.IO) {
        onComplete(executeBoundedBatch(requests, batchSize.coerceIn(1, 4), ::processRequest))
    }

    internal fun completionPayload(request: BatchRequest): String {
        require(request.prompt.isNotBlank() && request.prompt.length <= 1_000_000)
        require(request.maxTokens in 1..131_072 && request.temperature.isFinite() && request.temperature >= 0)
        return JSONObject().put("prompt", request.prompt).put("n_predict", request.maxTokens).put("temperature", request.temperature.toDouble()).put("stream", false).toString()
    }

    private suspend fun processRequest(request: BatchRequest): String {
        val call = Request.Builder().url("${serverUrl.trimEnd('/')}/completion")
            .post(completionPayload(request).toRequestBody("application/json".toMediaType()))
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }.build()
        val response = client.newCall(call).awaitBoundedText()
        check(response.successful) { "Local batch request failed: HTTP ${response.code}" }
        val json = JSONObject(response.text)
        require(json.opt("content") is String && !json.has("error")) { "Local server did not return decoded completion text." }
        return json.getString("content")
    }

    /** Compatibility for saved JSONL jobs; each row still uses the selected local server. */
    suspend fun batchProcess(jsonlContent: String): String = withContext(Dispatchers.IO) {
        require(jsonlContent.length <= 8 * 1024 * 1024)
        val rows = jsonlContent.lineSequence().filter { it.isNotBlank() }.take(1001).toList()
        require(rows.size <= 1000)
        val requests = rows.map { line ->
            val row = JSONObject(line)
            BatchRequest(listOf(row.optString("context", ""), row.getString("prompt")).filter { it.isNotBlank() }.joinToString("\n"), row.optInt("n_tokens_to_predict", 1024), row.optDouble("temperature", .7).toFloat())
        }
        executeBoundedBatch(requests, batchSize.coerceIn(1, 4), ::processRequest).joinToString("\n") { result ->
            when (result) {
                is BatchResult.Success -> JSONObject().put("content", result.response).toString()
                is BatchResult.Failure -> JSONObject().put("error", result.error).toString()
            }
        }
    }

    fun parseLlamaResponse(response: String, totalRequests: Int): List<BatchResult> {
        require(totalRequests in 0..1000 && response.length <= 8 * 1024 * 1024)
        val lines = response.lineSequence().filter { it.isNotBlank() }.take(totalRequests).toList()
        return (0 until totalRequests).map { index ->
            try {
                val row = JSONObject(lines.getOrNull(index) ?: error("No response received"))
                require(!row.has("error")) { row.optString("error", "Local request failed") }
                require(row.opt("content") is String) { "Token IDs require the server's model tokenizer; decoded text was not supplied." }
                BatchResult.Success(index, row.getString("content"))
            } catch (error: Exception) {
                BatchResult.Failure(index, error.message ?: "Invalid local response")
            }
        }
    }
}
