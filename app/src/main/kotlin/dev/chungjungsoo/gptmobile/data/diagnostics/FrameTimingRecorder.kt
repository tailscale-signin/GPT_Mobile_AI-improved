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
    private val runPhases = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun phase(runId: String, phase: String) {
        if (!AppLogRecorder.enabled.value) return
        if (runPhases.size < 16 || runPhases.containsKey(runId.take(128))) runPhases[runId.take(128)] = phase.take(32)
    }
    fun finished(runId: String) {
        runPhases.remove(runId.take(128))
    }

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
        var frames = 0
        var totalObservedFrames = 0L
        val coverageId = screenIds.incrementAndGet()
        val runCoverage = FrameRunCoverage()
        var slow = 0
        var dropped = 0
        var totalNs = 0L
        var maximumNs = 0L
        val histogram = FrameDurationHistogram()
        val work = FrameWorkBreakdown()
        var lastReportMs = SystemClock.elapsedRealtime()
        val effects = FrameEffectsObservation(FrameEffectPressure.guard, lastReportMs)
        val display = activity.display
        var frameBudgetNs = FrameDeadlineBudget.fromRefreshRate(display?.refreshRate)
        var previousMemory: DiagnosticExportMemory? = null
        var minimumDeadlineNs = Long.MAX_VALUE
        var maximumDeadlineNs = 0L
        var fallbackDeadlines = 0
        // A WeakHashMap value must not capture its Activity key through the callback.
        val activityName = activity.javaClass.simpleName
        fun report(nowMs: Long, reason: String) {
            if (frames == 0) return
            val windowMs = nowMs - lastReportMs
            lastReportMs = nowMs
            if (AppLogRecorder.enabled.value) {
                val memory = AndroidDiagnosticExportMemory.sample()
                val gcCount = previousMemory?.gcCount?.let { before -> memory.gcCount?.minus(before)?.takeIf { it >= 0 } }
                val gcDurationMs = previousMemory?.gcTimeMillis?.let { before -> memory.gcTimeMillis?.minus(before)?.takeIf { it >= 0 } }
                val refreshRateMillihz = display?.refreshRate?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
                AppLogRecorder.record("Rendering", "FRAME_TIMING · frames=$frames · jankFrames=$slow · droppedSamples=$dropped · windowMs=$windowMs · averageMs=${totalNs / frames / 1_000_000.0} · maxMs=${maximumNs / 1_000_000.0} · p50UpperMs=${histogram.percentileUpperMs(50)} · p95UpperMs=${histogram.percentileUpperMs(95)} · p99UpperMs=${histogram.percentileUpperMs(99)} · stallsOver100Ms=${histogram.stallsOver100Ms} · stallsOver500Ms=${histogram.stallsOver500Ms} · percentileOverflow=${histogram.overflow} · frameBudgetMs=${frameBudgetNs / 1_000_000.0} · minimumDeadlineNs=$minimumDeadlineNs · maximumDeadlineNs=$maximumDeadlineNs · fallbackDeadlines=$fallbackDeadlines · refreshRateMillihz=${refreshRateMillihz ?: "unavailable"} · buildVariant=${dev.chungjungsoo.gptmobile.BuildConfig.BUILD_TYPE} · phaseMaximumNs=${work.maximumNanos()} · slowFramePhaseTotalNs=${work.slowFrameNanos()} · phaseValuesMayOverlap=true · screen=${screens.maxByOrNull { it.key }?.value ?: activityName} · graphicsEffects=${RenderingEffectDiagnostics.snapshot()} · operation=${JavaHeapWorkGate.activeOperations} · javaHeapUsedBytes=${memory.javaUsedBytes} · javaHeapMaxBytes=${memory.javaMaxBytes} · nativeHeapBytes=${memory.nativeAllocatedBytes ?: "unavailable"} · gcCount=${gcCount ?: "unavailable"} · gcDurationMs=${gcDurationMs ?: "unavailable"} · gcCountersApproximate=true · coverageId=$coverageId · reportReason=$reason · sampledRuns=${runCoverage.snapshot().entries.joinToString { "${it.key}:${it.value}" }} · sampledRunsTruncated=${runCoverage.truncated} · activeRuns=${runPhases.entries.joinToString { "${it.key}:${it.value}" }}")
                previousMemory = memory
            } else {
                previousMemory = null
            }
            histogram.clear()
            work.clear()
            runCoverage.clear()
            frames = 0
            slow = 0
            dropped = 0
            totalNs = 0
            maximumNs = 0
            minimumDeadlineNs = Long.MAX_VALUE
            maximumDeadlineNs = 0
            fallbackDeadlines = 0
            // Refresh the fallback once per reporting window, not through a display
            // query for every frame. Valid observed deadlines always take precedence.
            frameBudgetNs = FrameDeadlineBudget.fromRefreshRate(display?.refreshRate)
        }
        var closed = false
        val reportTimer = object : Runnable {
            override fun run() {
                if (closed) return
                report(SystemClock.elapsedRealtime(), "interval")
                metricsHandler.postDelayed(this, 5000)
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
            frames++
            totalObservedFrames++
            runCoverage.observe(runPhases)
            minimumDeadlineNs = minOf(minimumDeadlineNs, deadline)
            maximumDeadlineNs = maxOf(maximumDeadlineNs, deadline)
            if (observedDeadline == null) fallbackDeadlines++
            histogram.add(duration)
            val slowFrame = duration > deadline
            if (slowFrame) slow++
            work.observe(FrameWorkBreakdown.Phase.INPUT, metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.ANIMATION, metrics.getMetric(FrameMetrics.ANIMATION_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.LAYOUT_MEASURE, metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.DRAW, metrics.getMetric(FrameMetrics.DRAW_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.SYNC, metrics.getMetric(FrameMetrics.SYNC_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.COMMAND_ISSUE, metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), slowFrame)
            work.observe(FrameWorkBreakdown.Phase.UNKNOWN_DELAY, metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION), slowFrame)
            dropped += lost
            totalNs += duration
            maximumNs = maxOf(maximumNs, duration)
            if (frames >= 120 && nowMs - lastReportMs >= 5000) {
                report(nowMs, "frame_threshold")
            }
        }
        listeners[activity] = ObservedWindow(listener, effects) {
            closed = true
            metricsHandler.removeCallbacks(reportTimer)
            report(SystemClock.elapsedRealtime(), "window_paused")
            AppLogRecorder.record("Rendering", "FRAME_COVERAGE · event=stopped · coverageId=$coverageId · activity=$activityName · frames=$totalObservedFrames · subsequentCoverage=PAUSED_WINDOW_NOT_OBSERVED")
        }
        resumedWindows.incrementAndGet()
        activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
        // Handler delays use uptime, but cooldowns use elapsed time. Recheck on
        // resume so time spent asleep/backgrounded cannot prolong a stale fallback.
        metricsHandler.post {
            AppLogRecorder.record("Rendering", "FRAME_COVERAGE · event=started · coverageId=$coverageId · activity=$activityName · priorCoverage=NO_CLAIM_ACROSS_LIFECYCLE_GAP")
            metricsHandler.postDelayed(reportTimer, 5000)
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
