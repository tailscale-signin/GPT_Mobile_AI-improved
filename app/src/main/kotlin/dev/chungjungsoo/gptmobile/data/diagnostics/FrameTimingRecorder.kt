package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Local frame guard with opt-in diagnostic reports; no per-frame logging or persisted samples. */
internal object FrameTimingRecorder {
    private val screenIds = java.util.concurrent.atomic.AtomicLong()
    private val screens = java.util.concurrent.ConcurrentHashMap<Long, String>()
    fun screen(name: String): () -> Unit {
        val id = screenIds.incrementAndGet()
        screens[id] = name.take(64)
        while (screens.size > 16) screens.keys.minOrNull()?.let(screens::remove)
        return { screens.remove(id) }
    }
    fun activeReportSurface(): String? = when (screens.entries.maxByOrNull { it.key }?.value) {
        "Chat" -> "CHAT"
        "Debug/Logs" -> "DEBUG_LOGS"
        "GitHubWorkspace" -> "GITHUB_WORKSPACE"
        else -> null
    }

    private val runPhases = FrameRunPhases()
    fun phase(runId: String, phase: String) {
        runPhases.observe(runId, phase, AppLogRecorder.operationCaptureEpoch())
    }
    fun finished(runId: String) {
        runPhases.finished(runId)
    }
    internal fun clearRunHistory() = runPhases.clear()

    private val metricsHandler by lazy { Handler(HandlerThread("FrameDiagnostics").apply { start() }.looper) }
    private var active = java.lang.ref.WeakReference<Activity>(null)
    private val listeners = WeakHashMap<Activity, ObservedWindow>()
    private val resumedWindows = AtomicInteger()

    // Only the metrics handler reads or writes this flag and posts recovery callbacks.
    private var recoveryCheckPending = false

    private val recoveryCheck = object : Runnable {
        override fun run() {
            recoveryCheckPending = false
            updateEffectRecovery()
        }
    }

    private fun updateEffectRecovery() {
        val delay = if (resumedWindows.get() > 0) {
            FrameEffectPressure.guard.recoverAfterCooldown(SystemClock.elapsedRealtime())
        } else {
            null
        }
        if (delay == null) {
            if (recoveryCheckPending) {
                metricsHandler.removeCallbacks(recoveryCheck)
                recoveryCheckPending = false
            }
        } else if (!recoveryCheckPending) {
            recoveryCheckPending = true
            metricsHandler.postDelayed(recoveryCheck, delay)
        }
    }

    fun start(activity: Activity) {
        active = java.lang.ref.WeakReference(activity)
        if (listeners.containsKey(activity)) return
        val coverageId = screenIds.incrementAndGet()
        val window = FrameTimingWindow(SystemClock.elapsedRealtime())
        var operationWindowStartedNanos = System.nanoTime()
        val effects = FrameEffectsObservation(FrameEffectPressure.guard, window.startedMs)
        val display = activity.display
        var frameBudgetNs = FrameDeadlineBudget.fromRefreshRate(display?.refreshRate)
        var previousMemory: DiagnosticExportMemory? = null
        var previousCpuMillis: Long? = null
        fun alignEpoch(nowMs: Long): Long? {
            val epoch = AppLogRecorder.operationCaptureEpoch()
            if (window.align(epoch, nowMs)) {
                previousMemory = null
                previousCpuMillis = null
                operationWindowStartedNanos = System.nanoTime()
            }
            return epoch
        }
        // A WeakHashMap value must not capture its Activity key through the callback.
        val activityName = activity.javaClass.simpleName
        fun report(nowMs: Long, reason: String, force: Boolean = false) {
            val diagnosticCaptureEpoch = alignEpoch(nowMs)
            if (!force && !window.due(nowMs)) return
            // coverageId owns the lifecycle; each periodic flush has a distinct window identity.
            val windowId = "$coverageId:${window.sequence}"
            if (window.frames == 0) {
                diagnosticCaptureEpoch?.let { epoch ->
                    AppLogRecorder.recordCaptured(epoch, "Rendering", "FRAME_CALLBACK_COVERAGE · coverageId=$coverageId · windowId=$windowId · reportReason=$reason · evidence=${window.callbackCoverage(nowMs)}")
                }
                window.clear(nowMs)
                previousMemory = null
                previousCpuMillis = null
                operationWindowStartedNanos = System.nanoTime()
                return
            }
            val windowMs = nowMs - window.startedMs
            if (diagnosticCaptureEpoch != null) {
                val operationWindowEndedNanos = System.nanoTime()
                val observedWindowStartNanos = operationWindowStartedNanos
                val operations = CombinedOperationTrace.frameWindow(observedWindowStartNanos, operationWindowEndedNanos)
                operationWindowStartedNanos = operationWindowEndedNanos
                AppLogRecorder.recordCaptured(diagnosticCaptureEpoch, "Rendering", "FRAME_OPERATION_COVERAGE · coverageId=$coverageId · windowId=$windowId · coverage=SAMPLED_INSTRUMENTED_OPERATIONS · minimumDurationNs=${CombinedOperationTrace.FRAME_SAMPLE_THRESHOLD_NANOS} · causalAttribution=false · truncated=${operations.truncated} · observed=${operations.samples.joinToString { "${it.operation}:${it.traceId}" }} · observedRuns=${operations.samples.mapNotNull { it.runId }.distinct().joinToString()} · windowStartMonoNs=$observedWindowStartNanos · windowEndMonoNs=$operationWindowEndedNanos")
                val operationContexts = operations.contextReport()
                if (operationContexts.contexts.isNotEmpty() || operationContexts.truncated) {
                    AppLogRecorder.recordCaptured(diagnosticCaptureEpoch, "Rendering", "FRAME_OPERATION_CONTEXTS · coverageId=$coverageId · windowId=$windowId · causalAttribution=false · contextTruncated=${operationContexts.truncated} · maximumContexts=${FrameOperationCoverage.MAX_REPORTED_CONTEXTS} · maximumContextCharacters=${FrameOperationCoverage.MAX_CONTEXT_CHARACTERS} · windowStartMonoNs=$observedWindowStartNanos · windowEndMonoNs=$operationWindowEndedNanos · contexts=${operationContexts.contexts}")
                }
                val memory = AndroidDiagnosticExportMemory.sample()
                AppLogRecorder.recordCaptured(diagnosticCaptureEpoch, "Rendering", "FRAME_CALLBACK_COVERAGE · coverageId=$coverageId · windowId=$windowId · reportReason=$reason · evidence=${window.callbackCoverage(nowMs)}")
                AppLogRecorder.recordCaptured(diagnosticCaptureEpoch, "Rendering", "FRAME_WINDOW_EVIDENCE · coverageId=$coverageId · windowId=$windowId · evidence=${window.evidence.snapshot(diagnosticCaptureEpoch)}")
                val gcCount = previousMemory?.gcCount?.let { before -> memory.gcCount?.minus(before)?.takeIf { it >= 0 } }
                val gcDurationMs = previousMemory?.gcTimeMillis?.let { before -> memory.gcTimeMillis?.minus(before)?.takeIf { it >= 0 } }
                val cpuMillis = android.os.Process.getElapsedCpuTime()
                val processCpuDelta = previousCpuMillis?.let { (cpuMillis - it).takeIf { elapsed -> elapsed >= 0 } }
                previousCpuMillis = cpuMillis
                val phaseMetrics = window.work.maximumNanos().entries.joinToString(" · ") { (phase, value) -> "frame_phase_${phase.lowercase()}_ns=${value ?: "unavailable"}" }
                val refreshRateMillihz = display?.refreshRate?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
                AppLogRecorder.recordCaptured(diagnosticCaptureEpoch, "Rendering", "FRAME_TIMING · frames=${window.frames} · jankFrames=${window.slow} · droppedSamples=${window.dropped} · windowMs=$windowMs · averageMs=${window.totalNs / window.frames / 1_000_000.0} · maxMs=${window.maximumNs / 1_000_000.0} · p50UpperMs=${window.histogram.percentileUpperMs(50)} · processCpuMs=${processCpuDelta ?: "unavailable"} · p95UpperMs=${window.histogram.percentileUpperMs(95)} · p99UpperMs=${window.histogram.percentileUpperMs(99)} · stallsOver100Ms=${window.histogram.stallsOver100Ms} · stallsOver500Ms=${window.histogram.stallsOver500Ms} · percentileOverflow=${window.histogram.overflow} · frameBudgetMs=${frameBudgetNs / 1_000_000.0} · minimumDeadlineNs=${window.minimumDeadlineNs} · maximumDeadlineNs=${window.maximumDeadlineNs} · fallbackDeadlines=${window.fallbackDeadlines} · refreshRateMillihz=${refreshRateMillihz ?: "unavailable"} · buildVariant=${dev.chungjungsoo.gptmobile.BuildConfig.BUILD_TYPE} · $phaseMetrics · phaseMaximumNs=${window.work.maximumNanos()} · slowFramePhaseTotalNs=${window.work.slowFrameNanos()} · phaseValuesMayOverlap=true · screen=${screens.maxByOrNull { it.key }?.value ?: activityName} · graphicsEffects=${RenderingEffectDiagnostics.snapshot()} · operation=${JavaHeapWorkGate.activeOperations} · javaHeapUsedBytes=${memory.javaUsedBytes} · javaHeapMaxBytes=${memory.javaMaxBytes} · nativeHeapBytes=${memory.nativeAllocatedBytes ?: "unavailable"} · gcCount=${gcCount ?: "unavailable"} · gcDurationMs=${gcDurationMs ?: "unavailable"} · gcCountersApproximate=true · coverageId=$coverageId · windowId=$windowId · reportReason=$reason · sampledRuns=${window.runCoverage.snapshot(diagnosticCaptureEpoch).entries.joinToString { "${it.key}:${it.value}" }} · sampledRunsTruncated=${window.runCoverage.truncated} · activeRuns=${runPhases.snapshot(diagnosticCaptureEpoch).entries.joinToString { "${it.key}:${it.value}" }}")
                previousMemory = memory
            } else {
                previousMemory = null
                previousCpuMillis = null
                operationWindowStartedNanos = System.nanoTime()
            }
            window.clear(nowMs)
            // Refresh the fallback once per reporting window, not through a display
            // query for every frame. Valid observed deadlines always take precedence.
            frameBudgetNs = FrameDeadlineBudget.fromRefreshRate(display?.refreshRate)
        }
        var closed = false
        val reportTimer = object : Runnable {
            override fun run() {
                if (closed) return
                val nowMs = SystemClock.elapsedRealtime()
                report(nowMs, "interval")
                metricsHandler.postDelayed(this, window.nextDelayMs(nowMs))
            }
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0)
            val observedDeadline = FrameDeadlineBudget.observed(metrics.getMetric(FrameMetrics.DEADLINE))
            val deadline = observedDeadline ?: frameBudgetNs
            val nowMs = SystemClock.elapsedRealtime()
            // First draws are expected to include startup work. Keep them in the raw
            // report below without treating them as interactive rendering pressure.
            if (!effects.observe(duration, deadline, metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, nowMs)) {
                return@OnFrameMetricsAvailableListener
            }
            updateEffectRecovery()
            val captureEpoch = alignEpoch(nowMs)
            if (!window.observe(duration, deadline, lost, observedDeadline == null, nowMs)) return@OnFrameMetricsAvailableListener
            val observedPhases = runPhases.snapshot(captureEpoch)
            window.runCoverage.observe(observedPhases, captureEpoch)
            window.evidence.observe(System.nanoTime(), duration, observedPhases.keys, captureEpoch) {
                val runtime = Runtime.getRuntime()
                (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
            }
            val slowFrame = duration > deadline
            window.work.observe(FrameWorkBreakdown.Phase.INPUT, metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.ANIMATION, metrics.getMetric(FrameMetrics.ANIMATION_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.LAYOUT_MEASURE, metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.DRAW, metrics.getMetric(FrameMetrics.DRAW_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.SYNC, metrics.getMetric(FrameMetrics.SYNC_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.COMMAND_ISSUE, metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), slowFrame)
            if (android.os.Build.VERSION.SDK_INT >= 31) window.work.observe(FrameWorkBreakdown.Phase.GPU, metrics.getMetric(FrameMetrics.GPU_DURATION), slowFrame)
            window.work.observe(FrameWorkBreakdown.Phase.UNKNOWN_DELAY, metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION), slowFrame)
            if (window.frames >= 120 && window.due(nowMs)) {
                report(nowMs, "frame_threshold")
            }
        }
        listeners[activity] = ObservedWindow(listener, effects) {
            closed = true
            metricsHandler.removeCallbacks(reportTimer)
            report(SystemClock.elapsedRealtime(), "window_paused", force = true)
            window.epoch?.let { epoch ->
                AppLogRecorder.recordCaptured(epoch, "Rendering", "FRAME_COVERAGE · event=stopped · coverageId=$coverageId · activity=$activityName · frames=${window.observedFrames} · subsequentCoverage=PAUSED_WINDOW_NOT_OBSERVED")
            }
        }
        resumedWindows.incrementAndGet()
        activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
        // Handler delays use uptime, but cooldowns use elapsed time. Recheck on
        // resume so time spent asleep/backgrounded cannot prolong a stale fallback.
        metricsHandler.post {
            AppLogRecorder.record("Rendering", "FRAME_COVERAGE · event=started · coverageId=$coverageId · activity=$activityName · priorCoverage=NO_CLAIM_ACROSS_LIFECYCLE_GAP")
            metricsHandler.postDelayed(reportTimer, window.nextDelayMs(SystemClock.elapsedRealtime()))
            updateEffectRecovery()
        }
    }

    fun refresh() {
        Handler(Looper.getMainLooper()).post {
            active.get()?.let(::start)
        }
    }

    fun stop(activity: Activity) {
        if (active.get() === activity) active.clear()
        listeners.remove(activity)?.let {
            it.effects.stop()
            activity.window.removeOnFrameMetricsAvailableListener(it.listener)
            metricsHandler.post(it.finish)
            if (resumedWindows.decrementAndGet() == 0) metricsHandler.post { updateEffectRecovery() }
        }
    }

    private data class ObservedWindow(val listener: Window.OnFrameMetricsAvailableListener, val effects: FrameEffectsObservation, val finish: () -> Unit)
}
