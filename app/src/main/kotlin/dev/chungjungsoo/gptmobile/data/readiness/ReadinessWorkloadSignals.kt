package dev.chungjungsoo.gptmobile.data.readiness

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent from repository construction. Leases close exactly once on every terminal path. */
object ReadinessWorkloadSignals {
    private val count = MutableStateFlow(0)
    val active = count.asStateFlow()

    @Synchronized
    fun enter(): AutoCloseable {
        count.value++
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) leave() }
    }

    @Synchronized
    private fun leave() {
        count.value = (count.value - 1).coerceAtLeast(0)
    }
}
