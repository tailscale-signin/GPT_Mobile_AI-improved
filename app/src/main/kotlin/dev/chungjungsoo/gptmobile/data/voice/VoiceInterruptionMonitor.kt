package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class VoiceInterruptionStatus { OFF, STARTING, READY, DETECTED, UNAVAILABLE }
internal data class VoicePreRoll(val samples: FloatArray, val capturedNanos: Long)

internal interface VoiceInterruptionInput {
    val interruptionState: StateFlow<VoiceInterruptionStatus>
    fun startInterruptionMonitoring(outputLevel: () -> Float?, interrupted: () -> Unit)
    fun stopInterruptionMonitoring()
    fun afterInterruptionStopped(released: () -> Unit) {
        stopInterruptionMonitoring()
        released()
    }
}

/** Sustained speech-like activity on an echo-cancelled input, not a single level spike. */
internal class VoiceInterruptionDetector {
    private var observedMillis = 0L
    private var candidateMillis = 0L
    private var noise = .003f

    fun observe(samples: FloatArray, outputLevel: Float?): Boolean {
        if (samples.isEmpty()) return false
        val millis = samples.size * 1000L / 16_000
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
        val crossings = (1 until samples.size).count { (samples[it - 1] >= 0) != (samples[it] >= 0) }.toFloat() / samples.size
        val floor = maxOf(.009f, noise * 3.2f, (outputLevel ?: 0f).coerceIn(0f, 1f) * .12f)
        val speech = rms > floor && crossings in .008f..0.42f
        observedMillis += millis
        if (!speech) noise = (noise * .96f + rms.coerceAtMost(.02f) * .04f).coerceIn(.001f, .02f)
        // Allow the platform AEC to settle when the output route starts.
        if (observedMillis < 240) return false
        candidateMillis = if (speech) candidateMillis + millis else (candidateMillis - millis * 2).coerceAtLeast(0)
        return candidateMillis >= 200
    }
}

/** Microphone-only monitor: never creates an ASR model while synthesis owns native work. */
internal class VoiceInterruptionMonitor(
    context: Context,
    private val captureFactory: () -> LocalVoiceCapture = { AndroidVoiceCapture(context.applicationContext, allowPlayback = true) }
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "VoiceInterruption").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(VoiceInterruptionStatus.OFF)
    val state: StateFlow<VoiceInterruptionStatus> = mutableState.asStateFlow()

    @Volatile private var capture: LocalVoiceCapture? = null

    fun start(outputLevel: () -> Float?, interrupted: (List<VoicePreRoll>) -> Unit) {
        if (closed.get()) return
        stop()
        val ticket = generation.incrementAndGet()
        mutableState.value = VoiceInterruptionStatus.STARTING
        try {
            worker.execute {
                val lease = "voice-interruption:${UUID.randomUUID()}"
                var detected: List<VoicePreRoll>? = null
                var current: LocalVoiceCapture? = null
                var acquired = false
                try {
                    // Recognition can have delivered its final text just before its final
                    // native cleanup. Only microphone ownership is needed by this monitor.
                    repeat(20) {
                        if (acquired || closed.get() || generation.get() != ticket) return@repeat
                        acquired = VoiceMicrophoneLease.acquire(lease)
                        if (!acquired) Thread.sleep(25)
                    }
                    if (!acquired || closed.get() || generation.get() != ticket) return@execute
                    current = captureFactory()
                    current.configure(40)
                    capture = current
                    current.start()
                    if (closed.get() || generation.get() != ticket) return@execute
                    mutableState.value = VoiceInterruptionStatus.READY
                    val detector = VoiceInterruptionDetector()
                    val leadIn = ArrayDeque<VoicePreRoll>()
                    var frames = 0
                    while (!closed.get() && generation.get() == ticket) {
                        val samples = current.read()
                        if (samples.isEmpty()) continue
                        val retain = samples.copyOf()
                        current.recycle(samples)
                        leadIn.addLast(VoicePreRoll(retain, System.nanoTime()))
                        frames += retain.size
                        while (frames > 16_000 && leadIn.size > 1) frames -= leadIn.removeFirst().samples.size
                        if (detector.observe(retain, outputLevel())) {
                            detected = leadIn.toList()
                            break
                        }
                    }
                } catch (_: Exception) {
                    if (generation.get() == ticket) mutableState.value = VoiceInterruptionStatus.UNAVAILABLE
                } finally {
                    runCatching { current?.close() }
                    if (capture === current) capture = null
                    if (acquired) VoiceMicrophoneLease.release(lease)
                    val speech = detected
                    if (speech != null) {
                        main.post {
                            if (!closed.get() && generation.get() == ticket) {
                                mutableState.value = VoiceInterruptionStatus.DETECTED
                                interrupted(speech)
                            }
                        }
                    } else if (generation.get() == ticket && mutableState.value != VoiceInterruptionStatus.UNAVAILABLE) {
                        mutableState.value = VoiceInterruptionStatus.OFF
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            mutableState.value = VoiceInterruptionStatus.OFF
        }
    }

    fun stop(released: (() -> Unit)? = null) {
        generation.incrementAndGet()
        runCatching { capture?.stop() }
        mutableState.value = VoiceInterruptionStatus.OFF
        if (released != null) {
            try {
                // A stop only unblocks AudioRecord. The single worker closes it and
                // returns its microphone lease before admitting the next recognizer.
                worker.execute { main.post { released() } }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                if (worker.isTerminated) main.post { released() }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        worker.shutdown()
    }
}
