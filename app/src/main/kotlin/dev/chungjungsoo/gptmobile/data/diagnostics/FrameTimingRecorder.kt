package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import java.util.WeakHashMap

/** Opt-in frame timing for streaming, transitions and overlays; no per-frame log spam. */
internal object FrameTimingRecorder {
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
    private val listeners = WeakHashMap<Activity, Window.OnFrameMetricsAvailableListener>()

    fun start(activity: Activity) {
        active = java.lang.ref.WeakReference(activity)
        if (!AppLogRecorder.enabled.value || listeners.containsKey(activity)) return
        var frames = 0
        var slow = 0
        var dropped = 0
        var totalNs = 0L
        var maximumNs = 0L
        val durations = ArrayList<Long>(2048)
        var lastReportMs = SystemClock.elapsedRealtime()
        val frameBudgetNs = (1_000_000_000.0 / (activity.display?.refreshRate ?: 60f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            if (!AppLogRecorder.enabled.value) return@OnFrameMetricsAvailableListener
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0)
            frames++
            if (durations.size < 2048) durations.add(duration)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: frameBudgetNs
            if (duration > deadline) slow++
            dropped += lost
            totalNs += duration
            maximumNs = maxOf(maximumNs, duration)
            val nowMs = SystemClock.elapsedRealtime()
            if (frames >= 120 && nowMs - lastReportMs >= 5000) {
                lastReportMs = nowMs
                val sorted = durations.sorted()
                fun percentile(percent: Int): Double = sorted.getOrElse(((sorted.size - 1) * percent / 100).coerceAtLeast(0)) { 0L } / 1_000_000.0
                AppLogRecorder.record("Rendering", "FRAME_TIMING · frames=$frames · jankFrames=$slow · droppedSamples=$dropped · averageMs=${totalNs / frames / 1_000_000.0} · maxMs=${maximumNs / 1_000_000.0} · p50Ms=${percentile(50)} · p95Ms=${percentile(95)} · p99Ms=${percentile(99)} · measuredSamples=${sorted.size} · buildType=${dev.chungjungsoo.gptmobile.BuildConfig.BUILD_TYPE} · refreshRate=${activity.display?.refreshRate ?: 60f} · frameBudgetMs=${frameBudgetNs / 1_000_000.0} · activeRuns=${runPhases.entries.joinToString { "${it.key}:${it.value}" }}")
                frames = 0
                slow = 0
                dropped = 0
                totalNs = 0
                maximumNs = 0
                durations.clear()
            }
        }
        listeners[activity] = listener
        activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler)
    }

    fun refresh() {
        Handler(Looper.getMainLooper()).post {
            if (AppLogRecorder.enabled.value) {
                active.get()?.let(::start)
            } else {
                listeners.keys.toList().forEach { activity -> listeners.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) } }
            }
        }
    }

    fun stop(activity: Activity) {
        if (active.get() === activity) active.clear()
        listeners.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
    }
}
