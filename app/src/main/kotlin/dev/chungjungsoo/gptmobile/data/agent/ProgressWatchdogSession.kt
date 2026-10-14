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
internal data class ProgressObservation(
    val atMillis: Long,
    val meaningful: Boolean,
    val terminal: Boolean = false,
    val stage: ObservedProgressStage = ObservedProgressStage.WAITING_FOR_MODEL,
    val stageStarted: Long = atMillis,
    val firstProgressAt: Long? = null,
    val visible: Boolean = false,
    val completedTools: Int = 0
)

/**
 * Heartbeats cannot extend a stalled request. Productive reasoning is progress, not
 * a visible answer. The caller's total-run deadline remains authoritative.
 */
internal fun AgentProviderSession.withProgressWatchdog(
    firstProgressMillis: Long = 180_000L,
    idleMillis: Long = 90_000L,
    roundMillis: Long = Long.MAX_VALUE,
    settings: ProgressDeadlineSettings = ProgressDeadlineSettings(),
    history: AdaptiveProgressHistory = AdaptiveProgressHistory.shared,
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
            val seenTransitions = hashSetOf<String>()
            val firstStageTimes = mutableMapOf(ObservedProgressStage.WAITING_FOR_MODEL to started)
            var generationStarted = started
            var generationObserved = false
            var lastCheckpoint = -1
            fun newTransition(key: String): Boolean = seenTransitions.size < 4096 && seenTransitions.add(key)
            var observedInputTokens = 0L
            var observedOutputTokens = 0L
            fun advancingCounters(input: Long?, output: Long?): Boolean {
                val advanced = (input != null && input > observedInputTokens) || (output != null && output > observedOutputTokens)
                observedInputTokens = maxOf(observedInputTokens, input ?: 0)
                observedOutputTokens = maxOf(observedOutputTokens, output ?: 0)
                return advanced
            }
            val dispatch = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext]
            fun observationKey(): String? = dispatch?.receipts?.lastOrNull()?.let { receipt ->
                val bucket = java.lang.Long.highestOneBit(receipt.promptTokens.coerceAtLeast(1))
                dev.chungjungsoo.gptmobile.data.research.researchHash("${receipt.runtime}|${receipt.role}|${receipt.permissionRevision}|$bucket")
            }
            val upstream = transportScope.launch {
                try {
                    delegate.streamRound(tools, exchanges).collect { event ->
                        val terminal = event == ProviderEvent.Completed || event is ProviderEvent.Failed
                        val meaningful = when (event) {
                            is ProviderEvent.TextDelta -> event.text.isNotEmpty()
                            is ProviderEvent.ThinkingDelta -> event.text.isNotEmpty()
                            is ProviderEvent.ToolCall -> event.callId.isNotBlank() && newTransition("tool_started:${event.callId}")
                            is ProviderEvent.ToolResult -> event.call.callId.isNotBlank() && newTransition("tool_completed:${event.call.callId}")
                            is ProviderEvent.Usage -> event.cumulative && advancingCounters(event.inputTokens?.toLong(), event.outputTokens?.toLong())
                            is ProviderEvent.LocalMetrics -> advancingCounters(event.metrics.native?.prefillTokens?.toLong(), event.metrics.native?.decodeTokens?.toLong())
                            is ProviderEvent.GatewayProgressUpdate -> {
                                val progress = event.progress
                                when (progress.event) {
                                    "tool_started", "tool_completed", "tool_failed" -> !progress.toolCallId.isNullOrBlank() && newTransition("${progress.event}:${progress.toolCallId}")
                                    "checkpoint" -> progress.checkpoint?.let { checkpoint ->
                                        (checkpoint > lastCheckpoint).also { if (it) lastCheckpoint = checkpoint }
                                    } == true
                                    else -> false
                                }
                            }
                            else -> false
                        }
                        while (true) {
                            val prior = observation.get()
                            if (prior.terminal) return@collect
                            val now = nowMillis()
                            val stage = when (event) {
                                is ProviderEvent.TextDelta -> if (event.text.isNotEmpty()) ObservedProgressStage.ANSWER else prior.stage
                                is ProviderEvent.ThinkingDelta -> if (event.text.isNotEmpty()) ObservedProgressStage.REASONING else prior.stage
                                is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> ObservedProgressStage.TOOL
                                is ProviderEvent.PhaseChanged -> if (event.phase == dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase.PREFILL) ObservedProgressStage.PREFILL else prior.stage
                                is ProviderEvent.GatewayProgressUpdate -> when (event.progress.phase?.lowercase()) {
                                    "queued", "queue" -> ObservedProgressStage.QUEUED
                                    "loading", "load" -> ObservedProgressStage.LOADING
                                    "prefill" -> ObservedProgressStage.PREFILL
                                    else -> if (event.progress.event in setOf("tool_started", "tool_completed", "tool_failed")) ObservedProgressStage.TOOL else prior.stage
                                }
                                else -> prior.stage
                            }
                            val generationStage = stage !in setOf(ObservedProgressStage.QUEUED, ObservedProgressStage.LOADING, ObservedProgressStage.WAITING_FOR_MODEL)
                            if (generationStage && !generationObserved) {
                                if (prior.stage in setOf(ObservedProgressStage.QUEUED, ObservedProgressStage.LOADING)) generationStarted = now
                                generationObserved = true
                            }
                            val stageStart = if (generationStage) generationStarted else firstStageTimes.getOrPut(stage) { now }
                            val next = prior.copy(
                                atMillis = if (meaningful) now else prior.atMillis,
                                meaningful = prior.meaningful || meaningful,
                                terminal = terminal,
                                stage = stage,
                                stageStarted = stageStart,
                                firstProgressAt = prior.firstProgressAt ?: now.takeIf { meaningful },
                                visible = prior.visible || event is ProviderEvent.TextDelta && event.text.isNotEmpty(),
                                completedTools = prior.completedTools + if (meaningful && (event is ProviderEvent.ToolResult || event is ProviderEvent.GatewayProgressUpdate && event.progress.event in setOf("tool_completed", "tool_failed"))) 1 else 0
                            )
                            if (observation.compareAndSet(prior, next)) {
                                if (stage != prior.stage) dispatch?.progress(stage, now - started)
                                if (event == ProviderEvent.Completed) {
                                    next.firstProgressAt?.let { first -> observationKey()?.let { key -> history.record(key, first - started) } }
                                }
                                break
                            }
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
                    delay(minOf(1000L, firstProgressMillis, idleMillis, settings.firstProgressMillis ?: Long.MAX_VALUE, settings.meaningfulIdleMillis ?: Long.MAX_VALUE, settings.queueMillis ?: Long.MAX_VALUE, settings.loadMillis ?: Long.MAX_VALUE, settings.roundDeadlineMillis ?: Long.MAX_VALUE))
                    val prior = observation.get()
                    if (prior.terminal || !upstream.isActive) break
                    val now = nowMillis()
                    val totalExpired = now - started >= minOf(roundMillis, settings.roundDeadlineMillis ?: Long.MAX_VALUE)
                    val firstLimit = history.firstProgressMillis(observationKey(), firstProgressMillis, settings)
                    val phaseLimit = when (prior.stage) {
                        ObservedProgressStage.QUEUED -> settings.queueMillis ?: firstLimit
                        ObservedProgressStage.LOADING -> settings.loadMillis ?: firstLimit
                        else -> firstLimit
                    }
                    val idleExpired = if (prior.meaningful) {
                        now - prior.atMillis >= (settings.meaningfulIdleMillis ?: idleMillis)
                    } else {
                        now - prior.stageStarted >= phaseLimit
                    }
                    if ((totalExpired || idleExpired) && observation.compareAndSet(prior, prior.copy(terminal = true))) {
                        timedOut.set(true)
                        val reason = when {
                            totalExpired -> "RUN_DEADLINE_EXCEEDED"
                            prior.meaningful -> "GENERATION_STALLED"
                            prior.stage == ObservedProgressStage.QUEUED -> "QUEUE_DEADLINE_EXCEEDED"
                            prior.stage == ObservedProgressStage.LOADING -> "LOAD_DEADLINE_EXCEEDED"
                            else -> "MODEL_PROGRESS_TIMEOUT"
                        }
                        dispatch?.progress(prior.stage, now - started, dev.chungjungsoo.gptmobile.data.diagnostics.ProgressDeadlineCode.valueOf(reason))
                        send(
                            ProviderEvent.Failed(
                                "$reason: The model stopped making progress. Partial output and completed tool results are preserved; retry the unfinished stage.",
                                progressDeadline = ProgressDeadlineOutcome(reason, prior.stage, now - started, now - prior.atMillis, prior.visible, prior.completedTools)
                            )
                        )
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
