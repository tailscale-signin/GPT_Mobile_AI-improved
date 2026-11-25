package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceHistory
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceHealthObservation(history: VoicePerformanceHistory, sampleMemory: Boolean = false) {
    val context = LocalContext.current
    val window = remember(context) { context.voiceActivity()?.window }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    DisposableEffect(history, lifecycle, window) {
        if (window == null || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            onDispose { }
        } else {
            // Observe frames the app actually renders; never request extra frames to measure them.
            val thread = HandlerThread("VoiceFrameMetrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                history.framesDropped(dropped)
                val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (total >= 0) history.observe("frame_duration", total / 1_000_000, publish = false)
                val gpu = metrics.getMetric(FrameMetrics.GPU_DURATION)
                if (gpu >= 0) history.observe("gpu_duration", gpu / 1_000_000, publish = false)
            }
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            onDispose {
                window.removeOnFrameMetricsAvailableListener(listener)
                thread.quitSafely()
            }
        }
    }
    LaunchedEffect(history, lifecycle, sampleMemory) {
        if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val power = context.getSystemService(PowerManager::class.java)
        var nextMemorySample = 0L
        var memory: Int? = null
        while (isActive) {
            if (sampleMemory && SystemClock.elapsedRealtime() >= nextMemorySample) {
                memory = withContext(Dispatchers.IO) { runCatching { Debug.getPss() }.getOrNull()?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() }
                nextMemorySample = SystemClock.elapsedRealtime() + 10_000
            }
            history.health(power?.currentThermalStatus, memory)
            delay(2000)
        }
    }
}

@Composable
internal fun VoicePerformancePanel(history: VoicePerformanceHistory, latest: VoiceSynthesisFeedback? = null) {
    val snapshot by history.snapshots.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("voice_performance_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Measured performance", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = history::clear) { Text("Reset") }
        }
        Text("Latest ${snapshot.sampleCapacity} samples per stage · milliseconds · median / slower 95th percentile", style = MaterialTheme.typography.bodySmall)
        Text("Speech stage timings follow your local diagnostics privacy settings.", style = MaterialTheme.typography.bodySmall)
        Text("To compare engines, use the same phrase and audio route. Reset between runs, collect a cold start and several warm turns, then save each set of measurements.", style = MaterialTheme.typography.bodySmall)
        if (snapshot.rows.none { it.metric !in VoicePerformanceHistory.FRAME_METRICS }) Text("No speech timings are recorded in this sample window. Missing measurements remain unreported.", style = MaterialTheme.typography.bodyMedium)
        snapshot.rows.forEach { row ->
            val title = metricLabel(row.metric)
            val condition = listOfNotNull(engineLabel(row.engine), row.warm?.let { if (it) "warm" else "cold" }).joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(listOf(condition.takeIf(String::isNotEmpty), "${row.count} recent / ${row.observedSamples} observed").filterNotNull().joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${row.p50Ms} / ${row.p95Ms} ms", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()
        Text("Frame timing samples since reset: ${snapshot.sampledFrames} · dropped metrics callbacks: ${snapshot.droppedFrameSamples}", style = MaterialTheme.typography.bodySmall)
        Text("Capture queue: ${snapshot.captureQueueMs?.let { "$it ms" } ?: "Not measured"}", style = MaterialTheme.typography.bodySmall)
        Text("App memory: ${snapshot.processMemoryKb?.let { "${it / 1024} MB PSS" } ?: "Not measured"} · Thermal: ${thermalLabel(snapshot.thermalStatus)}", style = MaterialTheme.typography.bodySmall)
        Text("Frame and GPU durations describe Android graphics. CPU, GPU and NPU utilization and full conversation latency are not measured. Speech end uses the detector’s audio boundary, including its smoothing window. Playback timing identifies the output clock; acoustic speaker delay requires a device loopback measurement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VoicePerformanceExportControl(snapshot, latest)
    }
}

private fun metricLabel(metric: String): String = when (metric) {
    "capture_ready" -> "Microphone readiness"
    "gate_wait" -> "Native engine wait"
    "verification" -> "Model verification"
    "model_load" -> "Model load"
    "speech_end" -> "Detected speech end to final text"
    "final_to_request" -> "Final text to accepted request"
    "request_to_text" -> "Request to first AI text"
    "request_to_lookup_audio" -> "Accepted request to spoken search update"
    "request_to_answer_audio" -> "Accepted request to first spoken answer"
    "text_to_publishable" -> "First text to stable spoken phrase"
    "publishable_to_queue" -> "Stable phrase to speech queue"
    "text_to_queue" -> "First text to speech queue"
    "warmup" -> "Engine preparation"
    "queue_to_synthesis" -> "Queue to synthesis"
    "queue_to_pcm" -> "Queue to first prepared audio"
    "pcm_to_audio" -> "Prepared audio to playback"
    "synthesis_to_audio" -> "Synthesis to playback"
    "text_to_audio" -> "First AI text to playback"
    "frame_interval" -> "Frame interval"
    "frame_duration" -> "Rendered frame duration"
    "gpu_duration" -> "GPU frame duration"
    else -> metric
}

private fun thermalLabel(status: Int?): String = when (status) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Not measured"
}

private fun engineLabel(engine: String): String? = when (engine) {
    "SESSION" -> null
    "SYSTEM" -> "Android speech"
    "LOCAL" -> "Moonshine"
    "KOKORO" -> "Speech backend unreported"
    "KOKORO_CPU" -> "Kokoro CPU"
    "MEDIAPIPE_KOKORO" -> "Kokoro streaming · processor unverified"
    "MEDIAPIPE" -> "Streaming speech · processor unverified"
    "MEDIAPIPE_PHONON" -> "Phonon streaming · processor unverified"
    "MEDIAPIPE_POCKET" -> "Pocket streaming · processor unverified"
    "QNN_GPU" -> "Adreno GPU"
    "QNN_HTP" -> "Hexagon NPU"
    "GATEWAY_CUDA" -> "Gateway CUDA"
    else -> null
}

private tailrec fun Context.voiceActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.voiceActivity()
    else -> null
}
