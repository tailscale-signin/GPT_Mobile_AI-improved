package dev.chungjungsoo.gptmobile.data.openrouter

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.service.BatchResult
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenRouterBatchClient(
    private val apiKey: String,
    private val maxConcurrentRequests: Int = 10,
    private val retryDelayMs: Long = 1000,
    private val timeoutMs: Long = 30000,
    private val baseUrl: String = "https://openrouter.ai/api/v1"
) {

    private val safeTimeoutMs = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong())
    private val client = OkHttpClient.Builder()
        .connectTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    fun batchesUrl(): String = try {
        val uri = URI(baseUrl)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: "openrouter.ai"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        "$scheme://$host$port/api/v1/batches"
    } catch (e: Exception) {
        "https://openrouter.ai/api/v1/batches"
    }

    /**
     * Serializes payload guaranteeing strict field order:
     * 1. endpoint
     * 2. model
     * 3. requests
     *
     * OpenRouter's stream-parser requires this order and returns 400 if wrong.
     */
    fun buildBatchPayload(
        endpoint: String,
        model: String,
        requests: List<NativeBatchRequest>
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"endpoint\":").append(JSONObject.quote(endpoint)).append(",")
        sb.append("\"model\":").append(JSONObject.quote(model)).append(",")
        sb.append("\"requests\":[")
        requests.forEachIndexed { index, req ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"custom_id\":").append(JSONObject.quote(req.customId)).append(",")
            sb.append("\"body\":").append(req.body.toString())
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * Submits a native asynchronous batch to OpenRouter via POST /api/v1/batches
     */
    suspend fun submit(
        endpoint: String = "/v1/chat/completions",
        model: String,
        requests: List<NativeBatchRequest>
    ): BatchMeta = withContext(Dispatchers.IO) {
        val payload = buildBatchPayload(endpoint, model, requests)
        val requestBody = payload.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(batchesUrl())
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val errorMsg = try {
                val json = JSONObject(responseBody)
                val err = json.optJSONObject("error")
                err?.optString("message", responseBody) ?: json.optString("message", responseBody)
            } catch (_: Exception) {
                responseBody
            }
            throw Exception("Batch submission failed (${response.code}): $errorMsg")
        }

        parseBatchMeta(responseBody)
    }

    /**
     * Retrieves status and inlined results (if completed) for a batch via GET /api/v1/batches/{id}
     */
    suspend fun status(batchId: String): BatchStatusResponse = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(batchId, "UTF-8")
        val request = Request.Builder()
            .url("${batchesUrl()}/$encodedId")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            if (response.code == 404) throw Exception("Batch not found ($batchId)")
            throw Exception("Status check failed (${response.code}): $responseBody")
        }

        parseBatchStatus(responseBody)
    }

    /**
     * Lists batches in the workspace via GET /api/v1/batches?limit={limit}
     */
    suspend fun list(limit: Int = 100): List<BatchMeta> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${batchesUrl()}?limit=$limit")
            .get()
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw Exception("List batches failed (${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        val dataArray = json.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<BatchMeta>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.getJSONObject(i)
            list.add(parseBatchMetaJson(item))
        }
        list
    }

    /**
     * Cancels an in-progress batch via POST /api/v1/batches/{id}/cancel
     */
    suspend fun cancel(batchId: String): Unit = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(batchId, "UTF-8")
        val emptyBody = ByteArray(0).toRequestBody(null)
        val request = Request.Builder()
            .url("${batchesUrl()}/$encodedId/cancel")
            .post(emptyBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Cancel failed (${response.code})")
        }
    }

    fun parseBatchMeta(jsonString: String): BatchMeta {
        val json = JSONObject(jsonString)
        return parseBatchMetaJson(json)
    }

    private fun parseBatchMetaJson(json: JSONObject): BatchMeta {
        val countsObj = json.optJSONObject("request_counts")
        return BatchMeta(
            id = json.optString("id", ""),
            status = json.optString("status", "unknown"),
            endpoint = json.optString("endpoint", null),
            model = json.optString("model", null),
            createdAt = json.optLong("created_at", 0L),
            totalRequests = countsObj?.optInt("total", 0) ?: 0,
            completedRequests = countsObj?.optInt("completed", 0) ?: 0,
            failedRequests = countsObj?.optInt("failed", 0) ?: 0
        )
    }

    fun parseBatchStatus(jsonString: String): BatchStatusResponse {
        val json = JSONObject(jsonString)
        val meta = parseBatchMetaJson(json)
        val resultsArray = json.optJSONArray("results")
        val results = if (resultsArray != null) {
            val list = mutableListOf<BatchResultEntry>()
            for (i in 0 until resultsArray.length()) {
                val resObj = resultsArray.getJSONObject(i)
                val customId = resObj.optString("custom_id", "")
                val resp = resObj.optJSONObject("response")?.toString() ?: resObj.optString("response", null)
                val err = resObj.optJSONObject("error")?.toString() ?: resObj.optString("error", null)
                list.add(BatchResultEntry(customId = customId, response = resp, error = err))
            }
            list
        } else {
            null
        }

        return BatchStatusResponse(meta = meta, results = results)
    }

    // --- Client-side parallel fallback execution ---

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

    suspend fun processRequest(request: BatchRequest): String = processSingleRequest(request)

    private fun processSingleRequest(request: BatchRequest): String {
        val messages = listOf(
            JSONObject().put("role", "user").put("content", request.prompt)
        )

        val body = JSONObject()
            .put("model", "meta-llama/Meta-Llama-3.1-8B-Instruct")
            .put("messages", JSONArray(messages))
            .put("temperature", request.temperature.toDouble())
            .put("max_tokens", request.maxTokens)

        val requestBody = body.toString().toRequestBody(
            "application/json".toMediaTypeOrNull()
        )

        val chatUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "${baseUrl.removeSuffix("/")}/chat/completions"
        }

        val requestObj = Request.Builder()
            .url(chatUrl)
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

data class NativeBatchRequest(
    val customId: String,
    val body: JSONObject
)

data class BatchMeta(
    val id: String,
    val status: String,
    val endpoint: String? = null,
    val model: String? = null,
    val createdAt: Long = 0L,
    val totalRequests: Int = 0,
    val completedRequests: Int = 0,
    val failedRequests: Int = 0
)

data class BatchStatusResponse(
    val meta: BatchMeta,
    val results: List<BatchResultEntry>? = null
)

data class BatchResultEntry(
    val customId: String,
    val response: String? = null,
    val error: String? = null
)
