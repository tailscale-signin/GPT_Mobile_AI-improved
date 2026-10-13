package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Deadline state uses one atomic snapshot, so progress and timeout cannot disagree. */
internal data class ProgressObservation(val atMillis: Long, val meaningful: Boolean, val terminal: Boolean = false)

/**
 * Heartbeats cannot extend a stalled request. Productive reasoning is progress, not
 * a visible answer. The caller's total-run deadline remains authoritative.
 */
internal fun AgentProviderSession.withProgressWatchdog(
    firstProgressMillis: Long = 180_000L,
    idleMillis: Long = 90_000L,
    roundMillis: Long = Long.MAX_VALUE,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
): AgentProviderSession {
    require(firstProgressMillis > 0 && idleMillis > 0 && roundMillis > 0)
    val delegate = this
    return object : AgentProviderSession {
        override val handlesToolsInternally = delegate.handlesToolsInternally

        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = channelFlow {
            val started = nowMillis()
            val observation = AtomicReference(ProgressObservation(started, false))
            val timedOut = AtomicBoolean(false)
            val finished = CompletableDeferred<Unit>()
            // Transport cleanup is isolated: a broken socket's non-cooperative close
            // must not withhold the terminal event. The producer keeps its own runtime
            // lease until it actually stops; the watchdog never releases a native lease.
            val transportScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
            var lastGatewayStep: String? = null
            val upstream = transportScope.launch {
                try {
                    delegate.streamRound(tools, exchanges).collect { event ->
                        val terminal = event == ProviderEvent.Completed || event is ProviderEvent.Failed
                        val meaningful = when (event) {
                            is ProviderEvent.TextDelta -> event.text.isNotEmpty()
                            is ProviderEvent.ThinkingDelta -> event.text.isNotEmpty()
                            is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> true
                            is ProviderEvent.GatewayProgressUpdate -> {
                                val progress = event.progress
                                val step = "${progress.event}:${progress.toolCallId}:${progress.checkpoint}:${progress.totalToolCalls}"
                                val changed = step != lastGatewayStep && progress.event in setOf("tool_started", "tool_completed", "tool_failed", "checkpoint")
                                if (changed) lastGatewayStep = step
                                changed
                            }
                            else -> false
                        }
                        while (true) {
                            val prior = observation.get()
                            if (prior.terminal) return@collect
                            val next = ProgressObservation(if (meaningful) nowMillis() else prior.atMillis, prior.meaningful || meaningful, terminal)
                            if (observation.compareAndSet(prior, next)) break
                        }
                        send(event)
                        if (terminal) channel.close()
                    }
                    channel.close()
                } catch (error: Throwable) {
                    if (!timedOut.get()) channel.close(error)
                    if (error is CancellationException) throw error
                } finally {
                    if (!timedOut.get()) finished.complete(Unit)
                }
            }
            val watchdog = launch {
                while (upstream.isActive) {
                    delay(minOf(1000L, firstProgressMillis, idleMillis))
                    val prior = observation.get()
                    if (prior.terminal || !upstream.isActive) break
                    val now = nowMillis()
                    val totalExpired = now - started >= roundMillis
                    val idleExpired = now - prior.atMillis >= if (prior.meaningful) idleMillis else firstProgressMillis
                    if ((totalExpired || idleExpired) && observation.compareAndSet(prior, prior.copy(terminal = true))) {
                        timedOut.set(true)
                        val reason = when {
                            totalExpired -> "RUN_DEADLINE_EXCEEDED"
                            prior.meaningful -> "GENERATION_STALLED"
                            else -> "MODEL_PROGRESS_TIMEOUT"
                        }
                        send(ProviderEvent.Failed("$reason: The model stopped making progress. Partial output and completed tool results are preserved; retry the unfinished stage."))
                        channel.close()
                        finished.complete(Unit)
                        upstream.cancel()
                        break
                    }
                }
            }
            try {
                // Wait for a terminal decision, not indefinitely for native/transport close.
                finished.await()
            } finally {
                watchdog.cancel()
                upstream.cancel()
                withContext(NonCancellable) { withTimeoutOrNull(5000L) { upstream.join() } }
                transportScope.cancel()
            }
        }
    }
}
