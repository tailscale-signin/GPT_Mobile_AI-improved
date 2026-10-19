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
import dev.chungjungsoo.gptmobile.data.openrouter.account.recordOpenRouterReceipts
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
    private val openRouterBatchCacheDao: OpenRouterBatchCacheDao,
    private val receiptStore: dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
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

        val connectionUid = inputData.getString(KEY_CONNECTION_UID) ?: return@withContext Result.failure()
        val expectedRevision = inputData.getString(KEY_CREDENTIAL_REVISION) ?: return@withContext Result.failure()
        val model = inputData.getString(KEY_MODEL)?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure()
        val settings = openRouterSettingsRepository.loadSettings(connectionUid)
        val apiKey = dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator.parseKeys(settings.apiKey).singleOrNull() ?: return@withContext Result.failure()
        val revision = dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity.revision(connectionUid, settings.baseUrl, apiKey)
        if (revision != expectedRevision || !settings.batchingEnabled) return@withContext Result.failure()
        val policy = settings.routing ?: dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting()
        val cacheKey = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterPolicy.cacheIdentity(
            connectionUid,
            settings.baseUrl,
            revision,
            model,
            kotlinx.serialization.json.buildJsonObject {
                put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
                put("temperature", kotlinx.serialization.json.JsonPrimitive(temperature))
                put("max_tokens", kotlinx.serialization.json.JsonPrimitive(maxTokens))
            }.toString(),
            kotlinx.serialization.json.Json.encodeToString(policy)
        )
        if (settings.cacheEnabled && settings.cacheTtlSeconds > 0) {
            openRouterBatchCacheDao.getByCacheKey(cacheKey)?.takeIf { System.currentTimeMillis() - it.timestamp in 0 until settings.cacheTtlSeconds * 1000L }?.let { cached ->
                return@withContext Result.success(resultData(cached.responseContent, requestId, cacheKey))
            }
        }
        val receiptContext = receiptStore.capture(
            dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterReceiptContext(
                connectionUid,
                revision,
                settings.baseUrl,
                "queue",
                model,
                "batch",
                java.util.UUID.randomUUID().toString()
            )
        )

        val client = OpenRouterBatchClient(
            apiKey = apiKey,
            maxConcurrentRequests = settings.batchSize,
            timeoutMs = 30_000,
            baseUrl = settings.baseUrl,
            policy = policy,
            noRemoteStorage = settings.noRemoteResponseStorage,
            onRemoteResponse = { body ->
                val chunk = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk>(body)
                }.getOrNull()
                if (chunk != null) kotlinx.coroutines.flow.flowOf(chunk).recordOpenRouterReceipts(receiptContext, receiptStore, captureAtDispatch = false).collect { }
            }
        )

        try {
            val response = client.processRequest(
                BatchRequest(
                    prompt = prompt,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    model = model
                )
            )

            // Cache response if requestId is provided and content size is within safety limits
            if (settings.cacheEnabled && settings.cacheTtlSeconds > 0 && response.isNotBlank() && response.length <= MAX_CACHE_SIZE) {
                openRouterBatchCacheDao.insertOrUpdate(
                    OpenRouterBatchCacheEntity(
                        cacheKey = cacheKey,
                        responseContent = response,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val outputData = resultData(response, requestId, cacheKey)

            Result.success(outputData)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "OpenRouter queue dispatch failed: ${e.javaClass.simpleName}")
            if (e is dev.chungjungsoo.gptmobile.data.openrouter.BatchHttpException && e.status == 429 && runAttemptCount < settings.maxRetries) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, "Queued request failed. Check the selected connection and routing policy.")
                        .putString(KEY_REQUEST_ID, requestId)
                        .build()
                )
            }
        }
    }

    private fun resultData(response: String, requestId: String, cacheKey: String): Data {
        val builder = Data.Builder().putString(KEY_REQUEST_ID, requestId)
        if (response.toByteArray(Charsets.UTF_8).size <= 6000) {
            builder.putString(KEY_RESPONSE, response)
        } else {
            val directory = java.io.File(applicationContext.filesDir, "openrouter_queue_results").apply { mkdirs() }
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > CACHE_EXPIRY_THRESHOLD_MS }?.forEach { it.delete() }
            val file = java.io.File(directory, "$cacheKey.json")
            android.util.AtomicFile(file).let { atomic ->
                val stream = atomic.startWrite()
                try {
                    stream.write(response.toByteArray())
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
            }
            builder.putString(KEY_RESULT_FILE, file.name)
        }
        return builder.build()
    }

    companion object {
        const val TAG = "OpenRouterBatchWorker"
        const val KEY_PROMPT = "prompt"
        const val KEY_API_KEY = "api_key"
        const val KEY_CONNECTION_UID = "connection_uid"
        const val KEY_CREDENTIAL_REVISION = "credential_revision"
        const val KEY_MODEL = "model"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_RESPONSE = "response"
        const val KEY_RESULT_FILE = "result_file"
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
            maxTokens: Int = 1024,
            connectionUid: String? = null,
            credentialRevision: String? = null,
            model: String? = null
        ) {
            require(apiKey.isNullOrBlank()) { "Do not persist credentials in background work." }
            require(!connectionUid.isNullOrBlank() && !credentialRevision.isNullOrBlank() && !model.isNullOrBlank()) { "Queued work requires an explicit connection, credential revision and model." }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val dataBuilder = Data.Builder()
                .putString(KEY_REQUEST_ID, requestId)
                .putString(KEY_PROMPT, prompt)
                .putFloat(KEY_TEMPERATURE, temperature)
                .putInt(KEY_MAX_TOKENS, maxTokens)

            dataBuilder.putString(KEY_CONNECTION_UID, connectionUid).putString(KEY_CREDENTIAL_REVISION, credentialRevision).putString(KEY_MODEL, model)

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
