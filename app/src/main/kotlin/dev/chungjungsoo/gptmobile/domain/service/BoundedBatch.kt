package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.BatchRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Fixed workers bound both coroutines and requests; results retain input order. */
internal suspend fun executeBoundedBatch(requests: List<BatchRequest>, concurrency: Int, execute: suspend (BatchRequest) -> String): List<BatchResult> = coroutineScope {
    require(requests.size <= 1000) { "Split batches larger than 1000 requests." }
    val cursor = AtomicInteger()
    val results = arrayOfNulls<BatchResult>(requests.size)
    List(minOf(requests.size, concurrency.coerceIn(1, 16))) {
        launch {
            while (true) {
                ensureActive()
                val index = cursor.getAndIncrement()
                if (index >= requests.size) break
                results[index] = try {
                    BatchResult.Success(index, execute(requests[index]))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    BatchResult.Failure(index, error.message ?: "Batch request failed")
                }
            }
        }
    }.joinAll()
    results.map(::requireNotNull)
}
