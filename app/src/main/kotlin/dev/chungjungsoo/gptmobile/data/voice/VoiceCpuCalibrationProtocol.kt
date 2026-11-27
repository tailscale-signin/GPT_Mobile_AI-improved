package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceCalibrationStop { CANCELLED, THERMAL, THERMAL_UNAVAILABLE }
internal class VoiceCalibrationInterrupted(val reason: VoiceCalibrationStop) : IllegalStateException(reason.name)

/** Short, matched CPU workloads; settings and order are frozen for an entire calibration. */
internal object VoiceCpuCalibrationProtocol {
    const val VERSION = 4
    const val ORDER_KEY = "cpu-calibration-order-v4"
    private val fixtures = listOf("the voice is ready.", "here are the main points.", "you can ask your next question.")

    fun rate(value: Float): Float = value.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f
    fun key(factory: String, voice: KokoroVoice, speed: Float): String = "threads:v$VERSION:$factory:${voice.name}:${voice.lexicon}:${rate(speed).toBits()}"

    fun candidates(processors: Int): List<Int> {
        val maximum = VoiceCpuSchedulingPolicy.deviceLimit(processors)
        return (listOf(1, 2, 3, 4, 6, 8, 12) + maximum).filter { it <= maximum }.distinct()
    }

    fun order(seed: Int, processors: Int): List<Int> {
        val counts = candidates(processors)
        val offset = Math.floorMod(seed, counts.size)
        val rotated = counts.drop(offset) + counts.take(offset)
        return if (Math.floorMod(seed, counts.size * 2) >= counts.size) rotated.reversed() else rotated
    }

    fun measure(
        seed: Int,
        speed: Float,
        active: () -> Boolean,
        thermalStatus: () -> Int?,
        prepare: (Int) -> Unit,
        generate: (String, Float) -> Unit,
        nanoTime: () -> Long,
        processors: Int = Runtime.getRuntime().availableProcessors()
    ): Map<Int, List<Long>> {
        fun checkActive() {
            if (!active()) throw VoiceCalibrationInterrupted(VoiceCalibrationStop.CANCELLED)
            val thermal = thermalStatus() ?: throw VoiceCalibrationInterrupted(VoiceCalibrationStop.THERMAL_UNAVAILABLE)
            if (thermal >= 2) throw VoiceCalibrationInterrupted(VoiceCalibrationStop.THERMAL)
        }
        val selectedRate = rate(speed)
        return buildMap {
            for (threads in order(seed, processors)) {
                checkActive()
                prepare(threads)
                checkActive()
                generate("this is a short voice sample.", selectedRate)
                checkActive()
                put(
                    threads,
                    fixtures.map { text ->
                        checkActive()
                        val started = nanoTime()
                        generate(text, selectedRate)
                        val elapsed = ((nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                        checkActive()
                        elapsed
                    }
                )
            }
            checkActive()
        }
    }
}
