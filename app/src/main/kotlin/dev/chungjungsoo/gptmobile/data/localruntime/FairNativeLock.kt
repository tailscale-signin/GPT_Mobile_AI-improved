package dev.chungjungsoo.gptmobile.data.localruntime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext

internal class NativeAdmissionRejected(val reason: String) : IllegalStateException(reason)

internal enum class NativeWorkClass { VISIBLE, HELPER, ENRICHMENT }

internal class NativeWorkPriority(val workClass: NativeWorkClass, val rootWorkId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeWorkPriority>
}

/** Non-preemptive ownership with visible-work priority, bounded bypass and aging. */
internal class FairNativeLock(private val now: () -> Long) {
    private class Waiter(val priority: NativeWorkPriority?, val enqueued: Long) {
        val signal = CompletableDeferred<Unit>()
        var granted = false
    }
    private val monitor = Any()
    private val waiting = mutableListOf<Waiter>()
    private var held = false
    private var visibleBurst = 0
    private var previousRoot: String? = null
    val isLocked: Boolean get() = synchronized(monitor) { held }

    fun tryLock(): Boolean = synchronized(monitor) {
        if (held || waiting.isNotEmpty()) {
            false
        } else {
            held = true
            true
        }
    }

    suspend fun lock() {
        val priority = currentCoroutineContext()[NativeWorkPriority]
        val waiter = synchronized(monitor) {
            if (!held) {
                held = true
                return
            }
            Waiter(priority, now()).also { waiting += it }
        }
        try {
            waiter.signal.await()
        } catch (error: Throwable) {
            val next = synchronized(monitor) {
                if (waiter.granted) {
                    nextLocked()
                } else {
                    waiting.remove(waiter)
                    null
                }
            }
            next?.signal?.complete(Unit)
            throw error
        }
    }

    fun unlock() {
        val next = synchronized(monitor) {
            check(held) { "Native resource is not owned" }
            nextLocked()
        }
        next?.signal?.complete(Unit)
    }

    private fun nextLocked(): Waiter? {
        if (waiting.isEmpty()) {
            held = false
            visibleBurst = 0
            previousRoot = null
            return null
        }
        val aged = waiting.firstOrNull { now() - it.enqueued >= 60_000L }
        val background = waiting.filter { it.priority?.workClass != null && it.priority.workClass != NativeWorkClass.VISIBLE }
        val priority = waiting.minOf { it.priority?.workClass?.ordinal ?: NativeWorkClass.VISIBLE.ordinal }
        val candidates = when {
            aged != null -> listOf(aged)
            visibleBurst >= 3 && background.isNotEmpty() -> background
            else -> waiting.filter { (it.priority?.workClass?.ordinal ?: NativeWorkClass.VISIBLE.ordinal) == priority }
        }
        val next = candidates.firstOrNull { it.priority?.rootWorkId != previousRoot } ?: candidates.first()
        waiting.remove(next)
        next.granted = true
        previousRoot = next.priority?.rootWorkId
        visibleBurst = if (next.priority?.workClass == null || next.priority.workClass == NativeWorkClass.VISIBLE) visibleBurst + 1 else 0
        return next
    }
}
