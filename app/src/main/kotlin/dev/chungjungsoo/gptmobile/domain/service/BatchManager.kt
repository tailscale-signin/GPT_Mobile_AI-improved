package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.data.llama.LlamaBatchClient
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterBatchClient
import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import kotlinx.coroutines.Dispatchers
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
        openRouterClient.processBatch(requests, onComplete)
    }

    private suspend fun processLlamaBatch(
        requests: List<BatchRequest>,
        onComplete: (List<BatchResult>) -> Unit
    ) {
        llamaClient.processBatch(requests, onComplete)
    }
}

sealed class BatchResult {
    data class Success(val index: Int, val response: String) : BatchResult()
    data class Failure(val index: Int, val error: String?) : BatchResult()
}
