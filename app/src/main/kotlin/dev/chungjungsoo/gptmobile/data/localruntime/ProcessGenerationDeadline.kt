package dev.chungjungsoo.gptmobile.data.localruntime

/** Only new work or a positively delivered tool response changes the active phase; heartbeats do not. */
internal class ProcessGenerationDeadline(
    private val startedAtMs: Long,
    val policy: RuntimeProcessDeadlinePolicy
) {
    private val counters = NativeProgressTracker()
    private var monotonicNow = startedAtMs
    private var lastWorkActiveMs = startedAtMs
    private var phaseStartedActiveMs = startedAtMs
    private var decoding = false
    private var sawOutput = false
    private var expiredKind: String? = null
    private val sampledCounts = linkedMapOf<Long, Long>()
    private var pausedMs = 0L
    private var pauseStartedMs: Long? = null
    private val tools = linkedMapOf<String, Long>()
    private val awaitingDelivery = mutableSetOf<String>()

    private fun now(value: Long): Long = maxOf(monotonicNow, value).also { monotonicNow = it }
    private fun activeTime(value: Long): Long = value - pausedMs - (pauseStartedMs?.let { value - it } ?: 0)

    @Synchronized fun toolStarted(callId: String, atMs: Long) {
        val value = now(atMs)
        if (expiration(value) != null) return
        if (tools.putIfAbsent(callId, value) == null && tools.size == 1 && policy.excludeToolWaitFromIdle) pauseStartedMs = value
    }

    @Synchronized fun toolFinished(callId: String, atMs: Long) {
        val value = now(atMs)
        if (expiration(value) != null) return
        if (tools.remove(callId) != null) awaitingDelivery += callId
        if (tools.isEmpty()) {
            pauseStartedMs?.let { pausedMs += value - it }
            pauseStartedMs = null
        }
    }

    @Synchronized fun toolResponseDelivered(callId: String, atMs: Long) {
        val value = now(atMs)
        if (expiration(value) != null) return
        val known = callId in tools || callId in awaitingDelivery
        toolFinished(callId, value)
        if (!known || !awaitingDelivery.remove(callId)) return
        decoding = false
        phaseStartedActiveMs = activeTime(value)
        lastWorkActiveMs = phaseStartedActiveMs
    }

    /** True only for the first nonempty output or positive native sample observed in this command. */
    @Synchronized fun observe(event: LocalRuntimeEvent, atMs: Long): Boolean {
        val value = now(atMs)
        if (expiration(value) != null) return false
        val text = when (event) {
            is LocalRuntimeEvent.TextDelta -> event.text
            is LocalRuntimeEvent.ThinkingDelta -> event.text
            else -> null
        }
        val progress = (event as? LocalRuntimeEvent.PhaseChanged)?.nativeProgress
        val advanced = counters.advance(progress)
        val sampledAdvanced = progress?.takeIf { it.operationId > 0 }?.let {
            val previous = sampledCounts[it.operationId] ?: 0
            sampledCounts[it.operationId] = maxOf(previous, it.sampledTokens)
            while (sampledCounts.size > 64) sampledCounts.remove(sampledCounts.keys.first())
            it.sampledTokens > previous
        } ?: false
        val output = !text.isNullOrEmpty() || sampledAdvanced
        if (output) decoding = true
        if (output || advanced) lastWorkActiveMs = activeTime(value)
        return (output && !sawOutput).also { if (output) sawOutput = true }
    }

    @Synchronized fun remainingMs(atMs: Long): Long {
        val value = now(atMs)
        if (expiredKind != null) return 0
        val wallRemaining = policy.absoluteMs - (value - startedAtMs)
        val since = if (decoding || policy.prefillProgressResetsIdle) lastWorkActiveMs else phaseStartedActiveMs
        val idleBudget = if (decoding) policy.decodeStallMs else policy.prefillMs
        val idleRemaining = idleBudget - (activeTime(value) - since)
        val toolRemaining = tools.values.minOfOrNull { policy.toolCallbackMs - (value - it) } ?: Long.MAX_VALUE
        return minOf(wallRemaining, idleRemaining, toolRemaining).coerceAtLeast(0)
    }

    @Synchronized fun expiration(atMs: Long): String? {
        val value = now(atMs)
        expiredKind?.let { return it }
        val kind = when {
            value - startedAtMs >= policy.absoluteMs -> "LOCAL_PROCESS_OPERATION_DEADLINE"
            tools.values.any { value - it >= policy.toolCallbackMs } -> "LOCAL_PROCESS_TOOL_CALLBACK_DEADLINE"
            remainingMs(value) > 0 -> null
            decoding -> "LOCAL_PROCESS_DECODE_STALL"
            else -> "LOCAL_PROCESS_PREFILL_DEADLINE"
        }
        expiredKind = kind
        return kind
    }
}

internal fun RuntimeExecutionReceipt.observingVisibleOutput(text: String, thinking: Boolean, atMs: Long): RuntimeExecutionReceipt =
    if (thinking || text.isEmpty() || firstVisibleTextAtElapsedMs != null) this else copy(firstVisibleTextAtElapsedMs = atMs, observedAtElapsedMs = atMs)

/** A schema compatibility proof belongs to its resolved backend; a recovery needs a new attempt. */
internal fun LocalConversationConfig.allowsAutomaticBackendRecovery(): Boolean = allowAutomaticRecovery && finalResponseSchemaJson == null
