package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.PowerManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The one-button suite uses the same warm fixtures, selection and preference key as Auto playback. */
internal suspend fun voiceBenchCpuCalibration(context: Context, variant: KokoroModelVariant, voice: KokoroVoice, rate: Float, active: () -> Boolean, interrupt: AtomicReference<(() -> Unit)?>, cleanupFailed: (Any) -> Unit): List<VoiceBenchObservation> {
    val models = KokoroModelPack.store(File(context.noBackupFilesDir, "voice-models"), variant = variant)
    models.refresh(false)
    if (!models.acquireUse()) return listOf(VoiceBenchObservation.unavailable(variant.name, "MODEL_NOT_INSTALLED"))
    val factory = TimedKokoroPhraseEngineFactory.forVariant(variant)
    val preferences = context.getSharedPreferences(VoiceCpuCalibrationProtocol.PREFERENCES, Context.MODE_PRIVATE)
    val power = context.getSystemService(PowerManager::class.java)
    val seed = preferences.getInt(VoiceCpuCalibrationProtocol.ORDER_KEY, 0)
    val period = VoiceCpuCalibrationProtocol.candidates(VoiceCpuSchedulingPolicy.deviceLimit()).size * 2
    val current = AtomicReference<VoicePhraseEngine?>()
    var cleanupConfirmed = true
    fun closeEngine() {
        val engine = current.getAndSet(null) ?: return
        try {
            engine.close()
        } catch (error: Throwable) {
            cleanupConfirmed = false
            cleanupFailed(listOf(engine, models))
            throw error
        }
    }
    interrupt.set {
        factory.cancelPending()
        current.get()?.cancel()
        Unit
    }
    try {
        val samples = VoiceNativeEngineGate.exclusive {
            try {
                VoiceCpuCalibrationProtocol.measure(seed, rate, active, { power?.currentThermalStatus }, { threads ->
                    closeEngine()
                    current.set(factory.create(models.modelDirectory(), voice.lexicon, threads))
                }, { text, speed ->
                    val audio = requireNotNull(current.get()).generate(text, voice.speakerId, speed)
                    require(audio.samples.isNotEmpty() && audio.samples.all(Float::isFinite) && audio.samples.any { it != 0f })
                    kokoroWordFrames(audio.durations, audio.wordTokens, audio.wordTokens.size / 2, audio.samples.size)
                }, System::nanoTime)
            } finally {
                closeEngine()
            }
        }
        val selected = VoiceCpuCalibration.select(samples)
        val applied = active() && power?.currentThermalStatus in 0..1
        if (applied) {
            preferences.edit()
                .putInt(VoiceCpuCalibrationProtocol.key(factory.identity, voice, rate), selected)
                .putInt(VoiceCpuCalibrationProtocol.ORDER_KEY, (Math.floorMod(seed, period) + 1) % period).apply()
        }
        return samples.map { (threads, durations) ->
            VoiceBenchObservation(
                "cpu-$threads",
                VoiceBenchStatus.MEASURED,
                metrics = buildMap {
                    put("threads", threads.toDouble())
                    durations.forEachIndexed { i, ms -> put("warmPhrase${i + 1}Ms", ms.toDouble()) }
                    put("selectedForAuto", if (applied && selected == threads) 1.0 else 0.0)
                }
            )
        }
    } catch (stopped: VoiceCalibrationInterrupted) {
        return listOf(VoiceBenchObservation.unavailable(variant.name, stopped.reason.name))
    } finally {
        interrupt.set(null)
        if (cleanupConfirmed) models.releaseUse()
    }
}
