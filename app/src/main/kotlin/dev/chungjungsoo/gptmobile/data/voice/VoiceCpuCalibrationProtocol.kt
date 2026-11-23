package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceCalibrationStop { CANCELLED, THERMAL, THERMAL_UNAVAILABLE }
internal class VoiceCalibrationInterrupted(val reason: VoiceCalibrationStop) : IllegalStateException(reason.name)

/** Short, matched CPU workloads; settings and order are frozen for an entire calibration. */
internal object VoiceCpuCalibrationProtocol {
    const val VERSION = 3
    const val ORDER_KEY = "cpu-calibration-order-v3"
    private val fixtures = listOf("the voice is ready.", "here are the main points.", "you can ask your next question.")

    fun rate(value: Float): Float = value.takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f
    fun key(factory: String, voice: KokoroVoice, speed: Float): String = "threads:v$VERSION:$factory:${voice.name}:${voice.lexicon}:${rate(speed).toBits()}"

    fun order(seed: Int): List<Int> {
        val offset = Math.floorMod(seed, 4)
        val rotated = (1..4).map { (it - 1 + offset) % 4 + 1 }
        return if (Math.floorMod(seed, 8) >= 4) rotated.reversed() else rotated
    }

    fun measure(
        seed: Int,
        speed: Float,
        active: () -> Boolean,
        thermalStatus: () -> Int?,
        prepare: (Int) -> Unit,
        generate: (String, Float) -> Unit,
        nanoTime: () -> Long
    ): Map<Int, List<Long>> {
        fun checkActive() {
            if (!active()) throw VoiceCalibrationInterrupted(VoiceCalibrationStop.CANCELLED)
            val thermal = thermalStatus() ?: throw VoiceCalibrationInterrupted(VoiceCalibrationStop.THERMAL_UNAVAILABLE)
            if (thermal >= 2) throw VoiceCalibrationInterrupted(VoiceCalibrationStop.THERMAL)
        }
        val selectedRate = rate(speed)
        return buildMap {
            for (threads in order(seed)) {
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
