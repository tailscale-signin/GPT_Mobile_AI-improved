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
        var slow = 0
        var dropped = 0
        var totalNs = 0L
        var maximumNs = 0L
        val histogram = FrameDurationHistogram()
        var lastReportMs = SystemClock.elapsedRealtime()
        val effects = FrameEffectsObservation(FrameEffectPressure.guard, lastReportMs)
        val frameBudgetNs = (1_000_000_000.0 / (activity.display?.refreshRate ?: 60f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: frameBudgetNs
            val nowMs = SystemClock.elapsedRealtime()
            // First draws are expected to include startup work. Keep them in the raw
            // report below without treating them as interactive rendering pressure.
            if (!effects.observe(duration, deadline, metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L, nowMs)) {
                return@OnFrameMetricsAvailableListener
            }
            updateEffectRecovery()
            frames++
            histogram.add(duration)
            if (duration > deadline) slow++
            dropped += lost
            totalNs += duration
            maximumNs = maxOf(maximumNs, duration)
            if (frames >= 120 && nowMs - lastReportMs >= 5000) {
                lastReportMs = nowMs
                if (AppLogRecorder.enabled.value) AppLogRecorder.record("Rendering", "FRAME_TIMING · frames=$frames · jankFrames=$slow · droppedSamples=$dropped · averageMs=${totalNs / frames / 1_000_000.0} · maxMs=${maximumNs / 1_000_000.0} · p95UpperMs=${histogram.percentileUpperMs(95)} · p99UpperMs=${histogram.percentileUpperMs(99)} · stallsOver100Ms=${histogram.stallsOver100Ms} · percentileOverflow=${histogram.overflow} · frameBudgetMs=${frameBudgetNs / 1_000_000.0} · screen=${screens.maxByOrNull { it.key }?.value ?: activity.javaClass.simpleName} · graphicsEffects=${RenderingEffectDiagnostics.snapshot()} · operation=${JavaHeapWorkGate.activeOperations} · javaHeapUsedBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} · javaHeapMaxBytes=${Runtime.getRuntime().maxMemory()} · activeRuns=${runPhases.entries.joinToString { "${it.key}:${it.value}" }}")
                histogram.clear()
                frames = 0
                slow = 0
                dropped = 0
                totalNs = 0
                maximumNs = 0
            }
        }
        listeners[activity] = ObservedWindow(listener, effects)
        resumedWindows.incrementAndGet()
        activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
        // Handler delays use uptime, but cooldowns use elapsed time. Recheck on
        // resume so time spent asleep/backgrounded cannot prolong a stale fallback.
        metricsHandler.post { updateEffectRecovery() }
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
            if (resumedWindows.decrementAndGet() == 0) metricsHandler.post { updateEffectRecovery() }
        }
    }

    private data class ObservedWindow(val listener: Window.OnFrameMetricsAvailableListener, val effects: FrameEffectsObservation)
}
