package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Gateway heartbeats cannot keep a stalled model round alive forever. */
internal fun AgentProviderSession.withProgressWatchdog(
    firstProgressMillis: Long = 60000L,
    idleMillis: Long = 60000L,
    roundMillis: Long = 180000L,
    nowMillis: () -> Long = { System.nanoTime() / 1000000L }
): AgentProviderSession {
    val delegate = this
    return object : AgentProviderSession {
        override val handlesToolsInternally = delegate.handlesToolsInternally

        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = channelFlow {
            val started = nowMillis()
            val lastProgress = AtomicLong(started)
            var progressed = false
            var lastGatewayStep: String? = null
            val upstream = launch {
                delegate.streamRound(tools, exchanges).collect { event ->
                    val meaningful = when (event) {
                        is ProviderEvent.TextDelta -> event.text.isNotBlank()
                        is ProviderEvent.ThinkingDelta -> event.text.isNotBlank()
                        is ProviderEvent.ToolCall, is ProviderEvent.ToolResult, ProviderEvent.Completed, is ProviderEvent.Failed -> true
                        is ProviderEvent.GatewayProgressUpdate -> {
                            val progress = event.progress
                            val step = "${progress.event}:${progress.toolCallId}:${progress.checkpoint}:${progress.totalToolCalls}"
                            val changed = step != lastGatewayStep && progress.event in setOf("tool_started", "tool_completed", "tool_failed", "checkpoint")
                            if (changed) lastGatewayStep = step
                            changed
                        }
                        else -> false
                    }
                    if (meaningful) {
                        progressed = true
                        lastProgress.set(nowMillis())
                    }
                    send(event)
                }
            }
            val watchdog = launch {
                while (upstream.isActive) {
                    delay(1000L)
                    if (!upstream.isActive) break
                    val now = nowMillis()
                    val budget = if (progressed) idleMillis else firstProgressMillis
                    if (now - started >= roundMillis || now - lastProgress.get() >= budget) {
                        upstream.cancelAndJoin()
                        send(ProviderEvent.Failed("Gateway generation stopped making progress. The partial response and completed tool results are saved; retry to continue from them."))
                        break
                    }
                }
            }
            upstream.join()
            // A timed-out upstream must let the watchdog publish its failure first.
            if (watchdog.isActive && nowMillis() - lastProgress.get() < (if (progressed) idleMillis else firstProgressMillis) && nowMillis() - started < roundMillis) {
                watchdog.cancelAndJoin()
            } else {
                watchdog.join()
            }
        }
    }
}
