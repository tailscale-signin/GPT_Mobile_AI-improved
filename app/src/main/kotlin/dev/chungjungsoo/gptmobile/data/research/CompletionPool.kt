package dev.chungjungsoo.gptmobile.data.research

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal suspend fun <T> completionPool(values: List<T>, concurrency: Int, work: suspend (T) -> Unit) = coroutineScope {
    val next = AtomicInteger()
    repeat(minOf(concurrency.coerceAtLeast(1), values.size)) {
        launch {
            while (true) {
                val index = next.getAndIncrement()
                if (index >= values.size) break
                work(values[index])
            }
        }
    }
}
