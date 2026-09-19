package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.data.llama.LlamaBatchClient
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient
import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class BatchManager(
    private val config: BatchConfig,
    private val openRouterClient: OpenRouterBatchClient = OpenRouterBatchClient(
        apiKey = config.apiKey,
        maxConcurrentRequests = config.openRouter.maxConcurrentRequests,
        retryDelayMs = config.openRouter.retryDelayMs,
        timeoutMs = config.openRouter.timeoutMs
    ),
    private val llamaClient: LlamaBatchClient = LlamaBatchClient(
        serverUrl = config.llama.serverUrl,
        apiKey = config.apiKey,
        batchSize = config.llama.batchSize
    )
) {

    suspend fun processBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        when (config.platform) {
            PlatformType.OPENROUTER -> processOpenRouterBatch(requests, onComplete)
            PlatformType.LLAMA_CPP -> processLlamaBatch(requests, onComplete)
            else -> throw UnsupportedOperationException("Batch not supported for ${config.platform}")
        }
    }

    private suspend fun processOpenRouterBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) {
        val results = mutableListOf<BatchResult>()

        // Process in parallel with concurrency limit
        val semaphore = Semaphore(config.openRouter.maxConcurrentRequests)

        requests.forEachIndexed { index, request ->
            semaphore.withPermit {
                try {
                    val response = openRouterClient.processRequest(request)
                    results.add(BatchResult.Success(index, response))
                } catch (e: Exception) {
                    results.add(BatchResult.Failure(index, e.message ?: "Unknown error"))
                }
            }
        }

        onComplete(results)
    }

    private suspend fun processLlamaBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) {
        val jsonlContent = requests.map { request ->
            """{"prompt": "${escapeJson(request.prompt)}", "context": "", "n_tokens_to_predict": ${request.maxTokens}}"""
        }.joinToString("\n")

        try {
            val response = llamaClient.batchProcess(jsonlContent)
            val results = llamaClient.parseLlamaResponse(response, requests.size)
            onComplete(results)
        } catch (e: Exception) {
            // Fallback to individual requests if batch fails
            processOpenRouterBatch(requests, onComplete)
        }
    }

    private fun escapeJson(input: String): String {
        return input.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

sealed class BatchResult {
    data class Success(val index: Int, val response: String) : BatchResult()
    data class Failure(val index: Int, val error: String?) : BatchResult()
}
