package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoicePerformanceRow(val metric: String, val engine: String, val warm: Boolean?, val count: Int, val p50Ms: Long, val p95Ms: Long, val observedSamples: Long = count.toLong())

data class VoicePerformanceSnapshot(
    val rows: List<VoicePerformanceRow> = emptyList(),
    val captureQueueMs: Long? = null,
    val thermalStatus: Int? = null,
    val processMemoryKb: Int? = null,
    val sampledFrames: Long = 0,
    val droppedFrameSamples: Long = 0,
    val sampleCapacity: Int = 64
)

/** Bounded, process-local numeric observations. No transcript, audio or identifier is retained. */
class VoicePerformanceHistory(private val capacity: Int = 64) {
    private data class Key(val metric: String, val engine: String, val warm: Boolean?)
    private val samples = linkedMapOf<Key, ArrayDeque<Long>>()
    private val observed = linkedMapOf<Key, Long>()
    private val mutable = MutableStateFlow(VoicePerformanceSnapshot(sampleCapacity = capacity.coerceIn(8, 256)))
    val snapshots = mutable.asStateFlow()
    private var context = mutable.value

    @Synchronized fun observe(metric: String, durationMs: Long, engine: String = "SESSION", warm: Boolean? = null, publish: Boolean = true) {
        if (metric !in METRICS || durationMs !in 0..86_400_000L || engine !in ENGINES) return
        val key = Key(metric, engine, warm)
        val queue = samples.getOrPut(key) { ArrayDeque() }
        observed[key] = (observed[key] ?: 0).coerceAtMost(Long.MAX_VALUE - 1) + 1
        queue.addLast(durationMs)
        while (queue.size > capacity.coerceIn(8, 256)) queue.removeFirst()
        if (metric in setOf("frame_interval", "frame_duration")) context = context.copy(sampledFrames = context.sampledFrames + 1)
        if (publish) publish()
    }

    @Synchronized fun queueAge(ageMs: Long) {
        context = context.copy(captureQueueMs = ageMs.coerceIn(0, 60_000))
    }

    /** Android reports callbacks dropped by the metrics collector, not rendered frames lost by the app. */
    @Synchronized fun framesDropped(count: Int) {
        if (count > 0) context = context.copy(droppedFrameSamples = context.droppedFrameSamples.coerceAtMost(Long.MAX_VALUE - count) + count)
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
                VoicePerformanceRow(key.metric, key.engine, key.warm, sorted.size, percentile(50), percentile(95), observed.getValue(key))
            }
        )
    }

    @Synchronized fun clear() {
        samples.clear()
        observed.clear()
        context = VoicePerformanceSnapshot(sampleCapacity = capacity.coerceIn(8, 256))
        mutable.value = context
    }

    internal companion object {
        val FRAME_METRICS = setOf("frame_interval", "frame_duration", "gpu_duration")
        val ENGINES = setOf("SESSION", "SYSTEM", "LOCAL", "KOKORO", "KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA")
        val METRICS = setOf("capture_ready", "gate_wait", "verification", "model_load", "speech_end", "final_to_request", "request_to_text", "text_to_publishable", "publishable_to_queue", "text_to_queue", "warmup", "queue_to_synthesis", "queue_to_pcm", "pcm_to_audio", "synthesis_to_audio", "text_to_audio", "frame_interval", "frame_duration", "gpu_duration")
    }
}
