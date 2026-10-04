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
        var lastReportMs = SystemClock.elapsedRealtime()
        val frameBudgetNs = (1_000_000_000.0 / (activity.display?.refreshRate ?: 60f)).toLong()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            if (!AppLogRecorder.enabled.value) return@OnFrameMetricsAvailableListener
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0)
            frames++
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: frameBudgetNs
            if (duration > deadline) slow++
            dropped += lost
            totalNs += duration
            maximumNs = maxOf(maximumNs, duration)
            val nowMs = SystemClock.elapsedRealtime()
            if (frames >= 120 && nowMs - lastReportMs >= 5000) {
                lastReportMs = nowMs
                AppLogRecorder.record("Rendering", "FRAME_TIMING · frames=$frames · jankFrames=$slow · droppedSamples=$dropped · averageMs=${totalNs / frames / 1_000_000.0} · maxMs=${maximumNs / 1_000_000.0} · frameBudgetMs=${frameBudgetNs / 1_000_000.0}")
                frames = 0
                slow = 0
                dropped = 0
                totalNs = 0
                maximumNs = 0
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
