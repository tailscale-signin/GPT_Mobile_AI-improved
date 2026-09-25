package dev.chungjungsoo.gptmobile.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.database.dao.OpenRouterBatchCacheDao
import dev.chungjungsoo.gptmobile.data.database.entity.OpenRouterBatchCacheEntity
import dev.chungjungsoo.gptmobile.data.openrouter.NativeBatchRequest
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkManager bridge for OpenRouter's native asynchronous Batch API.
 *
 * Submission and monitoring are intentionally separate WorkManager executions:
 * a provider batch may remain queued much longer than a single Android worker
 * should stay alive. Each monitor pass performs one GET and schedules another
 * bounded pass when the provider is still processing the batch.
 */
@HiltWorker
class OpenRouterBatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val openRouterSettingsRepository: OpenRouterSettingsRepository,
    private val openRouterBatchCacheDao: OpenRouterBatchCacheDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batchId = inputData.getString(KEY_BATCH_ID)
        if (batchId.isNullOrBlank() && inputData.getString(KEY_PROMPT).isNullOrBlank()) {
            Log.e(TAG, "Missing prompt for OpenRouter native batch")
            return@withContext Result.failure(errorData("Prompt is required."))
        }

        val settings = openRouterSettingsRepository.loadSettings()
        val apiKey = inputData.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() } ?: settings.apiKey
        if (apiKey.isBlank()) {
            Log.e(TAG, "Missing API key for OpenRouter batch operation")
            return@withContext Result.failure(errorData("OpenRouter API key is required."))
        }

        val client = OpenRouterBatchClient(
            apiKey = apiKey,
            maxConcurrentRequests = settings.batchSize,
            retryDelayMs = settings.retryDelayMs,
            timeoutMs = NETWORK_TIMEOUT_MS,
            baseUrl = settings.baseUrl
        )

        if (!batchId.isNullOrBlank()) {
            return@withContext monitorBatch(client, batchId)
        }

        submitBatch(client)
    }

    private suspend fun submitBatch(client: OpenRouterBatchClient): Result {
        val prompt = inputData.getString(KEY_PROMPT)
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        val model = inputData.getString(KEY_MODEL).orEmpty()
        val temperature = inputData.getFloat(KEY_TEMPERATURE, 0.7f)
        val maxTokens = inputData.getInt(KEY_MAX_TOKENS, 1024)

        if (prompt.isNullOrBlank()) {
            Log.e(TAG, "Missing prompt for OpenRouter native batch")
            return Result.failure(errorData("Prompt is required."))
        }
        if (model.isBlank()) {
            Log.e(TAG, "Missing model for OpenRouter native batch")
            return Result.failure(errorData("Model is required for OpenRouter Batch API."))
        }

        val cleanupThreshold = System.currentTimeMillis() - CACHE_EXPIRY_THRESHOLD_MS
        openRouterBatchCacheDao.deleteExpired(cleanupThreshold)

        return try {
            val body = JSONObject()
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", prompt)
                    )
                )
                .put("temperature", temperature.toDouble())
                .put("max_tokens", maxTokens)

            val customId = requestId.ifBlank { "request-${System.currentTimeMillis()}" }
            val meta = client.submit(
                endpoint = CHAT_COMPLETIONS_ENDPOINT,
                model = model,
                requests = listOf(NativeBatchRequest(customId = customId, body = body))
            )

            if (meta.id.isBlank()) {
                return Result.failure(errorData("OpenRouter returned an empty batch ID."))
            }

            val createdAt = System.currentTimeMillis()
            enqueueBatchMonitor(
                context = applicationContext,
                batchId = meta.id,
                requestId = customId,
                apiKey = inputData.getString(KEY_API_KEY),
                createdAtMs = createdAt
            )

            Result.success(
                Data.Builder()
                    .putString(KEY_REQUEST_ID, customId)
                    .putString(KEY_BATCH_ID, meta.id)
                    .putString(KEY_BATCH_STATUS, meta.status)
                    .putLong(KEY_BATCH_CREATED_AT_MS, createdAt)
                    .build()
            )
        } catch (error: Exception) {
            Log.e(TAG, "OpenRouter native batch submission failed: ${error.message}", error)
            retryOrFail(error)
        }
    }

    private suspend fun monitorBatch(
        client: OpenRouterBatchClient,
        batchId: String
    ): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        val createdAt = inputData.getLong(KEY_BATCH_CREATED_AT_MS, System.currentTimeMillis())
        if (System.currentTimeMillis() - createdAt > MAX_BATCH_LIFETIME_MS) {
            return Result.failure(errorData("OpenRouter batch exceeded the 25-hour monitoring window."))
        }

        return try {
            val status = client.status(batchId)
            when (status.meta.status.lowercase()) {
                "completed" -> {
                    val entry = status.results
                        ?.firstOrNull { requestId.isBlank() || it.customId == requestId }
                        ?: status.results?.firstOrNull()

                    if (!entry?.error.isNullOrBlank()) {
                        return Result.failure(errorData(entry?.error ?: "OpenRouter batch item failed."))
                    }

                    val response = client.extractAssistantContent(entry?.response)
                        ?: entry?.response
                        ?: ""

                    if (requestId.isNotBlank() && response.isNotBlank() && response.length <= MAX_CACHE_SIZE) {
                        openRouterBatchCacheDao.insertOrUpdate(
                            OpenRouterBatchCacheEntity(
                                cacheKey = requestId,
                                responseContent = response,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }

                    Result.success(
                        Data.Builder()
                            .putString(KEY_RESPONSE, response)
                            .putString(KEY_REQUEST_ID, requestId)
                            .putString(KEY_BATCH_ID, batchId)
                            .putString(KEY_BATCH_STATUS, status.meta.status)
                            .build()
                    )
                }

                "failed", "expired", "cancelled", "canceled" -> {
                    Result.failure(
                        errorData("OpenRouter batch ended with status: ${status.meta.status}")
                    )
                }

                else -> {
                    enqueueBatchMonitor(
                        context = applicationContext,
                        batchId = batchId,
                        requestId = requestId,
                        apiKey = inputData.getString(KEY_API_KEY),
                        createdAtMs = createdAt
                    )
                    Result.success(
                        workDataOf(
                            KEY_REQUEST_ID to requestId,
                            KEY_BATCH_ID to batchId,
                            KEY_BATCH_STATUS to status.meta.status
                        )
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "OpenRouter batch status check failed: ${error.message}", error)
            retryOrFail(error)
        }
    }

    private fun retryOrFail(error: Exception): Result {
        val maxRetries = runCatching { inputData.getInt(KEY_MAX_RETRIES, DEFAULT_MAX_RETRIES) }
            .getOrDefault(DEFAULT_MAX_RETRIES)
            .coerceIn(1, 10)
        return if (runAttemptCount < maxRetries) {
            Result.retry()
        } else {
            Result.failure(errorData(error.message ?: "OpenRouter batch operation failed."))
        }
    }

    private fun errorData(message: String): Data = Data.Builder()
        .putString(KEY_ERROR_MESSAGE, message)
        .putString(KEY_REQUEST_ID, inputData.getString(KEY_REQUEST_ID).orEmpty())
        .putString(KEY_BATCH_ID, inputData.getString(KEY_BATCH_ID))
        .build()

    companion object {
        const val TAG = "OpenRouterBatchWorker"
        const val KEY_PROMPT = "prompt"
        const val KEY_API_KEY = "api_key"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_MODEL = "model"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_RESPONSE = "response"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_BATCH_STATUS = "batch_status"
        const val KEY_BATCH_CREATED_AT_MS = "batch_created_at_ms"
        const val KEY_MAX_RETRIES = "max_retries"

        const val MAX_CACHE_SIZE = 1024 * 1024
        const val CACHE_EXPIRY_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_BATCH_LIFETIME_MS = 25L * 60 * 60 * 1000
        private const val MONITOR_DELAY_SECONDS = 20L
        private const val NETWORK_TIMEOUT_MS = 5L * 60 * 1000
        private const val DEFAULT_MAX_RETRIES = 3
        private const val CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions"

        fun enqueueBatchRequest(
            context: Context,
            requestId: String,
            prompt: String,
            model: String = "",
            apiKey: String? = null,
            temperature: Float = 0.7f,
            maxTokens: Int = 1024
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val dataBuilder = Data.Builder()
                .putString(KEY_REQUEST_ID, requestId)
                .putString(KEY_PROMPT, prompt)
                .putString(KEY_MODEL, model)
                .putFloat(KEY_TEMPERATURE, temperature)
                .putInt(KEY_MAX_TOKENS, maxTokens)

            if (!apiKey.isNullOrBlank()) {
                dataBuilder.putString(KEY_API_KEY, apiKey)
            }

            val workRequest = OneTimeWorkRequestBuilder<OpenRouterBatchWorker>()
                .setConstraints(constraints)
                .setInputData(dataBuilder.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag("openrouter-batch-$requestId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "openrouter-batch-submit-$requestId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        private fun enqueueBatchMonitor(
            context: Context,
            batchId: String,
            requestId: String,
            apiKey: String?,
            createdAtMs: Long
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val input = Data.Builder()
                .putString(KEY_BATCH_ID, batchId)
                .putString(KEY_REQUEST_ID, requestId)
                .putLong(KEY_BATCH_CREATED_AT_MS, createdAtMs)
                .apply {
                    if (!apiKey.isNullOrBlank()) putString(KEY_API_KEY, apiKey)
                }
                .build()

            val monitor = OneTimeWorkRequestBuilder<OpenRouterBatchWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setInitialDelay(MONITOR_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag("openrouter-batch-$requestId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "openrouter-batch-monitor-$batchId",
                ExistingWorkPolicy.REPLACE,
                monitor
            )
        }
    }
}
