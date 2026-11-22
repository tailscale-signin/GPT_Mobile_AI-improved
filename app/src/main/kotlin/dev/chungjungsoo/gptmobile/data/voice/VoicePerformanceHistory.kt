package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoicePerformanceRow(val metric: String, val engine: String, val warm: Boolean?, val count: Int, val p50Ms: Long, val p95Ms: Long)

data class VoicePerformanceSnapshot(
    val rows: List<VoicePerformanceRow> = emptyList(),
    val captureQueueMs: Long? = null,
    val thermalStatus: Int? = null,
    val processMemoryKb: Int? = null,
    val sampledFrames: Long = 0
)

/** Bounded, process-local numeric observations. No transcript, audio or identifier is retained. */
class VoicePerformanceHistory(private val capacity: Int = 64) {
    private data class Key(val metric: String, val engine: String, val warm: Boolean?)
    private val samples = linkedMapOf<Key, ArrayDeque<Long>>()
    private val mutable = MutableStateFlow(VoicePerformanceSnapshot())
    val snapshots = mutable.asStateFlow()
    private var context = VoicePerformanceSnapshot()

    @Synchronized fun observe(metric: String, durationMs: Long, engine: String = "SESSION", warm: Boolean? = null, publish: Boolean = true) {
        if (metric !in METRICS || durationMs !in 0..86_400_000L || engine !in ENGINES) return
        val queue = samples.getOrPut(Key(metric, engine, warm)) { ArrayDeque() }
        queue.addLast(durationMs)
        while (queue.size > capacity.coerceIn(8, 256)) queue.removeFirst()
        if (metric in setOf("frame_interval", "frame_duration")) context = context.copy(sampledFrames = context.sampledFrames + 1)
        if (publish) publish()
    }

    @Synchronized fun queueAge(ageMs: Long) {
        context = context.copy(captureQueueMs = ageMs.coerceIn(0, 60_000))
    }

    @Synchronized fun health(thermalStatus: Int?, processMemoryKb: Int?) {
        context = context.copy(thermalStatus = thermalStatus, processMemoryKb = processMemoryKb)
        publish()
    }

    @Synchronized fun publish() {
        mutable.value = context.copy(
            rows = samples.map { (key, values) ->
                val sorted = values.sorted()
                fun percentile(percent: Int) = sorted[((sorted.size * percent + 99) / 100 - 1).coerceAtLeast(0)]
                VoicePerformanceRow(key.metric, key.engine, key.warm, sorted.size, percentile(50), percentile(95))
            }
        )
    }

    @Synchronized fun clear() {
        samples.clear()
        context = VoicePerformanceSnapshot()
        mutable.value = context
    }

    private companion object {
        val ENGINES = setOf("SESSION", "SYSTEM", "LOCAL", "KOKORO", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA")
        val METRICS = setOf("capture_ready", "gate_wait", "verification", "model_load", "speech_end", "final_to_request", "request_to_text", "text_to_queue", "warmup", "queue_to_synthesis", "synthesis_to_audio", "text_to_audio", "frame_interval", "frame_duration", "gpu_duration")
    }
}
