package dev.chungjungsoo.gptmobile.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.database.dao.OpenRouterBatchCacheDao
import dev.chungjungsoo.gptmobile.data.database.entity.OpenRouterBatchCacheEntity
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterSettingsRepository
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class OpenRouterBatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val openRouterSettingsRepository: OpenRouterSettingsRepository,
    private val openRouterBatchCacheDao: OpenRouterBatchCacheDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prompt = inputData.getString(KEY_PROMPT)
        val requestId = inputData.getString(KEY_REQUEST_ID) ?: ""
        val temperature = inputData.getFloat(KEY_TEMPERATURE, 0.7f)
        val maxTokens = inputData.getInt(KEY_MAX_TOKENS, 1024)

        if (prompt.isNullOrBlank()) {
            Log.e(TAG, "Missing prompt for OpenRouterBatchWorker")
            return@withContext Result.failure()
        }

        // Clean up expired cache entries older than threshold
        val cleanupThreshold = System.currentTimeMillis() - CACHE_EXPIRY_THRESHOLD_MS
        openRouterBatchCacheDao.deleteExpired(cleanupThreshold)

        val settings = openRouterSettingsRepository.loadSettings()
        val apiKey = inputData.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() } ?: settings.apiKey

        if (apiKey.isBlank()) {
            Log.e(TAG, "Missing API key for OpenRouterBatchWorker")
            return@withContext Result.failure()
        }

        val client = OpenRouterBatchClient(
            apiKey = apiKey,
            maxConcurrentRequests = settings.batchSize,
            timeoutMs = settings.flushTimeoutMs
        )

        val maxAllowedRetries = settings.maxRetries.coerceAtLeast(1)

        try {
            val response = client.processRequest(
                BatchRequest(
                    prompt = prompt,
                    temperature = temperature,
                    maxTokens = maxTokens
                )
            )

            // Cache response if requestId is provided and content size is within safety limits
            if (requestId.isNotBlank() && response.isNotBlank() && response.length <= MAX_CACHE_SIZE) {
                openRouterBatchCacheDao.insertOrUpdate(
                    OpenRouterBatchCacheEntity(
                        cacheKey = requestId,
                        responseContent = response,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val outputData = Data.Builder()
                .putString(KEY_RESPONSE, response)
                .putString(KEY_REQUEST_ID, requestId)
                .build()

            Result.success(outputData)
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter batch dispatch failed: ${e.message}", e)
            if (runAttemptCount < maxAllowedRetries) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                        .putString(KEY_REQUEST_ID, requestId)
                        .build()
                )
            }
        }
    }

    companion object {
        const val TAG = "OpenRouterBatchWorker"
        const val KEY_PROMPT = "prompt"
        const val KEY_API_KEY = "api_key"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_RESPONSE = "response"
        const val KEY_ERROR_MESSAGE = "error_message"

        // 1MB max response cache size to avoid SQLite row/DB exhaustion
        const val MAX_CACHE_SIZE = 1024 * 1024
        // 7 days default cache retention threshold for background cleanup
        const val CACHE_EXPIRY_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000

        fun enqueueBatchRequest(
            context: Context,
            requestId: String,
            prompt: String,
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
                .putFloat(KEY_TEMPERATURE, temperature)
                .putInt(KEY_MAX_TOKENS, maxTokens)

            if (!apiKey.isNullOrBlank()) {
                dataBuilder.putString(KEY_API_KEY, apiKey)
            }

            val workRequest = OneTimeWorkRequestBuilder<OpenRouterBatchWorker>()
                .setConstraints(constraints)
                .setInputData(dataBuilder.build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
