package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.domain.model.BatchConfig
import dev.chungjungsoo.gptmobile.domain.model.PlatformType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class BatchManager(private val config: BatchConfig) {
    
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
        val semaphore = kotlinx.coroutines.sync.Semaphore(config.openRouter.maxConcurrentRequests)
        
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
            val results = parseLlamaResponse(response)
            onComplete(results)
        } catch (e: Exception) {
            // Fallback to individual requests if batch fails
            processOpenRouterBatch(requests, onComplete)
        }
    }
}

sealed class BatchResult {
    data class Success(val index: Int, val response: String) : BatchResult()
    data class Failure(val index: Int, val error: String?) : BatchResult()
}

data class BatchRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f
)

private fun escapeJson(input: String): String {
    return input.replace("\\", "\\\\")
                 .replace("\"", "\\\"")
                 .replace("\n", "\\n")
                 .replace("\r", "\\r")
                 .replace("\t", "\\t")
}