package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.io.File
import java.util.concurrent.Executors

/** The serial worker owns the JNI handle and PCM buffers. Commands and callbacks run on main. */
internal class KokoroVoiceOutput(
    context: Context,
    private val models: LocalVoiceModelStore,
    private val attributes: () -> AudioAttributes,
    private val rate: () -> Float
) : KokoroOutput {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroVoice").apply { isDaemon = true } }
    private val idleRelease = Runnable { worker.execute(::releaseModel) }
    private var closed = false

    @Volatile private var active: Playback? = null

    private var loaded: LoadedModel? = null

    private class LoadedModel(val engine: OfflineTts, val lexicon: String, val words: Set<String>)

    private class Playback(val focus: AudioFocusRequest, val watchdog: Runnable) {
        val startedAt = SystemClock.elapsedRealtime()
        var track: AudioTrack? = null

        @Volatile var queuedAudio = false

        var loggedFirstAudio = false
    }

    override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
        if (closed) return
        cancelPlayback()
        main.removeCallbacks(idleRelease)
        val playbackAttributes = attributes()
        val speed = rate().takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f
        lateinit var playback: Playback
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && active === playback) {
                    stop()
                    error("Audio paused because another app needs the speaker.", false)
                }
            }, main).build()
        playback = Playback(
            focus,
            Runnable {
                if (active === playback) {
                    stop()
                    timing("timeout", playback)
                    error("Kokoro playback timed out. Your answer remains in chat.", !playback.queuedAudio)
                }
            }
        )
        if (runCatching { audioManager?.requestAudioFocus(focus) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error("Audio output is unavailable. Your answer remains in chat.", false)
            return
        }
        active = playback
        renewWatchdog(playback)
        worker.execute {
            if (active !== playback) return@execute
            val succeeded = try {
                render(text, voice, speed, playbackAttributes, playback)
                true
            } catch (_: Exception) {
                releaseModel()
                false
            } catch (_: LinkageError) {
                releaseModel()
                false
            } catch (_: OutOfMemoryError) {
                releaseModel()
                false
            }
            main.post {
                if (active !== playback) return@post
                cancelPlayback()
                if (succeeded) {
                    timing("complete", playback)
                    main.postDelayed(idleRelease, 15_000)
                    complete()
                } else {
                    timing("failed", playback)
                    error("Kokoro playback stopped. Your answer remains in chat.", !playback.queuedAudio)
                }
            }
        }
    }

    private fun render(text: String, voice: KokoroVoice, speed: Float, attributes: AudioAttributes, playback: Playback) {
        val model = loadModel(voice, playback)
        val parts = requireNotNull(KokoroSpeechText.parts(text, model.words::contains))
        if (parts.isEmpty() || active !== playback) return
        val sampleRate = model.engine.sampleRate().also { check(it in 8_000..48_000) }
        val format = AudioFormat.Builder().setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBytes > 0)
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minBytes, sampleRate)).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            track.setStartThresholdInFrames(1)
            synchronized(playback) {
                if (active !== playback) return
                playback.track = track
            }
            var framesWritten = 0L
            for (part in parts) {
                if (active !== playback) return
                val generated = model.engine.generate(part, voice.speakerId, speed)
                if (active !== playback) return
                renewWatchdog(playback)
                val samples = generated.samples
                check(generated.sampleRate == sampleRate && samples.size in 1..sampleRate * 30)
                var offset = 0
                var lastWrite = SystemClock.elapsedRealtime()
                while (offset < samples.size) {
                    val written = synchronized(playback) {
                        if (active !== playback) return
                        track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING).also { count ->
                            if (count > 0 && !playback.queuedAudio) {
                                track.play()
                                playback.queuedAudio = true
                            }
                        }
                    }
                    check(written >= 0)
                    if (written == 0) {
                        check(SystemClock.elapsedRealtime() - lastWrite < 5_000)
                        Thread.sleep(10)
                    } else {
                        offset += written
                        lastWrite = SystemClock.elapsedRealtime()
                    }
                    playedFrames(track, playback)
                }
                framesWritten += offset
                renewWatchdog(playback)
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (active === playback && playedFrames(track, playback) < framesWritten) {
                check(SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10)
            }
        } finally {
            synchronized(playback) { playback.track = null }
            silence(track)
            track.release()
        }
    }

    private fun loadModel(voice: KokoroVoice, playback: Playback): LoadedModel {
        loaded?.takeIf { it.lexicon == voice.lexicon }?.let { return it }
        releaseModel()
        check(models.acquireUse())
        try {
            val directory = models.modelDirectory()
            val lexicon = File(directory, voice.lexicon)
            val words = lexicon.useLines { lines ->
                lines.map { it.substringBefore(' ') }.toHashSet()
            }
            check(active === playback)
            val engine = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(directory, KokoroModelPack.MODEL).absolutePath,
                            voices = File(directory, KokoroModelPack.VOICES).absolutePath,
                            tokens = File(directory, KokoroModelPack.TOKENS).absolutePath,
                            lexicon = lexicon.absolutePath
                        ),
                        numThreads = 2
                    )
                )
            )
            return LoadedModel(engine, voice.lexicon, words).also {
                loaded = it
                timing("model_ready", playback)
            }
        } finally {
            if (loaded == null) models.releaseUse()
        }
    }

    private fun playedFrames(track: AudioTrack, playback: Playback): Long {
        val frames = Integer.toUnsignedLong(track.playbackHeadPosition)
        if (frames > 0 && !playback.loggedFirstAudio) {
            playback.loggedFirstAudio = true
            timing("first_audio", playback)
        }
        return frames
    }

    private fun renewWatchdog(playback: Playback) {
        main.removeCallbacks(playback.watchdog)
        if (active === playback) main.postDelayed(playback.watchdog, 30_000)
    }

    private fun timing(stage: String, playback: Playback) {
        AppLogRecorder.record("VoiceTiming", "kokoro_$stage · durationMs=${SystemClock.elapsedRealtime() - playback.startedAt}", "I")
    }

    private fun silence(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
    }

    private fun releaseModel() {
        val model = loaded ?: return
        loaded = null
        try {
            runCatching { model.engine.free() }
        } finally {
            models.releaseUse()
        }
    }

    private fun cancelPlayback() {
        val playback = active ?: return
        active = null
        main.removeCallbacks(playback.watchdog)
        synchronized(playback) { playback.track?.let(::silence) }
        runCatching { audioManager?.abandonAudioFocusRequest(playback.focus) }
    }

    override fun stop() {
        if (closed) return
        cancelPlayback()
        main.removeCallbacks(idleRelease)
        worker.execute(::releaseModel)
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        worker.shutdown()
    }
}
