package dev.chungjungsoo.gptmobile.data.voice

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
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
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun createKokoroVoiceOutput(
    context: Context,
    models: LocalVoiceModelStore,
    attributes: () -> AudioAttributes,
    rate: () -> Float
): KokoroOutput? = try {
    KokoroVoiceOutput(context, models, attributes, rate)
} catch (error: LinkageError) {
    AppLogRecorder.record("VoiceEngine", "kokoro_startup_failed · type=${error.javaClass.simpleName}", "W")
    null
}

/** The synthesis worker owns the JNI model. The output worker drains one track per reply. */
@Suppress("DEPRECATION")
internal class KokoroVoiceOutput(
    context: Context,
    private val models: LocalVoiceModelStore,
    private val attributes: () -> AudioAttributes,
    private val rate: () -> Float
) : KokoroOutput {
    private val application = context.applicationContext
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val synthesis = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroSynthesis").apply { isDaemon = true } }
    private val output = Executors.newSingleThreadExecutor { task -> Thread(task, "KokoroPlayback").apply { isDaemon = true } }

    @Volatile private var closed = false

    private var warmupGeneration = 0L
    private var loaded: LoadedModel? = null

    @Volatile private var active: Playback? = null

    @Volatile private var warmupRequest: ModelKey? = null

    private var threadCount = 2

    override fun configureThreads(value: Int) {
        val bounded = value.coerceIn(2, 4)
        if (threadCount == bounded) return
        threadCount = bounded
        releaseWarmModel()
    }

    private data class ModelKey(val lexicon: String, val threads: Int)
    private class LoadedModel(val key: ModelKey, val engine: OfflineTts, val words: Set<String>) {
        val readyAt = SystemClock.elapsedRealtime()
    }

    private class Playback(
        val voice: KokoroVoice,
        val key: ModelKey,
        val focus: AudioFocusRequest,
        val watchdog: Runnable,
        val complete: () -> Unit,
        val error: (String, Boolean) -> Unit
    ) {
        val id = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val segments = ArrayBlockingQueue<String>(MAX_SEGMENTS)
        val pcm = ArrayBlockingQueue<FloatArray>(MAX_PCM_BATCHES)

        // D8 can emit invalid monitor regions for inlined synchronized blocks in playback cleanup.
        val trackLock = ReentrantLock()

        @Volatile var lastProgress = startedAt

        @Volatile var sealed = false

        @Volatile var synthesizing = true

        @Volatile var synthesisDone = false

        @Volatile var queuedAudio = false

        var track: AudioTrack? = null
        var underruns: Int? = null
        var firstPcm = false
        var firstFrame = false
        var inputChars = 0
    }

    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = trimMemory()
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimMemory()
        }
    }

    init {
        application.registerComponentCallbacks(memoryCallbacks)
    }

    private fun trimMemory() {
        main.post {
            active?.let { playback -> fail(playback, "memory_pressure", "Kokoro released its model to free memory. You can replay the reply.") }
            releaseWarmModel()
        }
    }

    override fun prewarm(voice: KokoroVoice) {
        if (closed || !models.isInstalled()) return
        val key = ModelKey(voice.lexicon, threadCount)
        if (warmupRequest == key) return
        warmupRequest = key
        val generation = ++warmupGeneration
        synthesis.execute {
            if (warmupRequest != key) return@execute
            try {
                VoiceNativeEngineGate.exclusive {
                    if (!closed && warmupRequest == key) loadModel(key)
                }
            } catch (_: Exception) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: LinkageError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            } catch (_: OutOfMemoryError) {
                main.post { if (warmupGeneration == generation) warmupRequest = null }
            }
        }
    }

    override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
        openReply(voice, complete, error)?.let {
            it.enqueue(text)
            it.finish()
        }
    }

    override fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit): VoiceReplyPlayback? {
        if (closed) return null
        active?.let { end(it, "replaced") }
        val playbackAttributes = attributes()
        val speed = rate().takeIf(Float::isFinite)?.coerceIn(.5f, 2f) ?: 1f
        lateinit var playback: Playback
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) fail(playback, "audio_focus_lost", "Audio paused because another app needs the speaker.", canFallback = false)
            }, main).build()
        val watchdog = object : Runnable {
            override fun run() {
                if (active !== playback) return
                if ((playback.synthesizing || playback.sealed || playback.segments.isNotEmpty()) &&
                    SystemClock.elapsedRealtime() - playback.lastProgress >= WATCHDOG_MS
                ) {
                    fail(playback, "timeout", "Kokoro playback timed out. Your answer remains in chat.")
                } else {
                    main.postDelayed(this, 1_000)
                }
            }
        }
        playback = Playback(voice, ModelKey(voice.lexicon, threadCount), focus, watchdog, complete, error)
        active = playback
        val focusGranted = runCatching { audioManager?.requestAudioFocus(focus) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusGranted) {
            main.post { fail(playback, "audio_focus_denied", "Audio output is unavailable. Your answer remains in chat.", canFallback = false) }
        } else {
            main.postDelayed(watchdog, 1_000)
            synthesis.execute { synthesize(playback, speed, playbackAttributes) }
        }
        return object : VoiceReplyPlayback {
            override fun enqueue(text: String) {
                if (active !== playback || playback.sealed || text.isBlank()) return
                val remaining = MAX_REPLY_CHARS - playback.inputChars
                if (remaining <= 0) return
                val bounded = text.take(remaining).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                if (bounded.isEmpty()) return
                playback.inputChars += bounded.length
                playback.lastProgress = SystemClock.elapsedRealtime()
                if (!playback.segments.offer(bounded)) {
                    fail(playback, "text_queue_full", "The voice playback queue is full. Your answer remains in chat.")
                }
            }

            override fun finish() {
                if (active === playback) playback.sealed = true
            }
        }
    }

    private fun synthesize(playback: Playback, speed: Float, playbackAttributes: AudioAttributes) {
        if (active !== playback) return
        val queueWait = SystemClock.elapsedRealtime() - playback.startedAt
        try {
            val model = loadModel(playback.key)
            if (active !== playback) return
            playback.lastProgress = SystemClock.elapsedRealtime()
            timing("model_ready", playback, "warm=${model.readyAt <= playback.startedAt} · queueWaitMs=$queueWait")
            val sampleRate = model.engine.sampleRate().also { check(it in 8_000..48_000) }
            output.execute { play(playback, playbackAttributes, sampleRate) }
            while (active === playback) {
                playback.synthesizing = false
                val segment = playback.segments.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
                if (segment == null) {
                    if (playback.sealed && playback.segments.isEmpty()) break
                    continue
                }
                playback.synthesizing = true
                val parts = requireNotNull(KokoroSpeechText.parts(segment, model.words::contains))
                for (part in parts) {
                    if (active !== playback) return
                    var callbackFrames = 0L
                    var invalidBatch = false
                    val generated = model.engine.generateWithCallback(part, playback.voice.speakerId, speed) { samples ->
                        callbackFrames += samples.size
                        if (callbackFrames > sampleRate * 30L) invalidBatch = true
                        if (invalidBatch || active !== playback) {
                            0
                        } else if (samples.isEmpty() || queuePcm(playback, samples, part.length)) {
                            1
                        } else {
                            0
                        }
                    }
                    if (active !== playback) return
                    check(!invalidBatch && generated.sampleRate == sampleRate)
                    if (callbackFrames == 0L) {
                        check(generated.samples.size in 1..sampleRate * 30)
                        if (!queuePcm(playback, generated.samples, part.length)) return
                    }
                }
            }
            playback.synthesisDone = true
        } catch (_: Exception) {
            main.post { fail(playback, "synthesis_failed", "Kokoro could not synthesize this reply. Your answer remains in chat.") }
        } catch (_: LinkageError) {
            releaseModel()
            main.post { fail(playback, "engine_failed", "Kokoro playback is unavailable. Your answer remains in chat.") }
        } catch (_: OutOfMemoryError) {
            releaseModel()
            main.post { fail(playback, "memory_pressure", "Kokoro could not allocate audio. Your answer remains in chat.") }
        } finally {
            playback.synthesizing = false
        }
    }

    /** Callback runs on the synthesis worker. A full queue applies backpressure off main. */
    private fun queuePcm(playback: Playback, samples: FloatArray, segmentChars: Int): Boolean {
        while (active === playback) {
            if (playback.pcm.offer(samples, POLL_MS, TimeUnit.MILLISECONDS)) {
                if (active !== playback) {
                    playback.pcm.remove(samples)
                    return false
                }
                playback.lastProgress = SystemClock.elapsedRealtime()
                if (!playback.firstPcm) {
                    playback.firstPcm = true
                    timing("first_pcm", playback, "segmentChars=$segmentChars")
                }
                return true
            }
        }
        return false
    }

    private fun play(playback: Playback, playbackAttributes: AudioAttributes, sampleRate: Int) {
        if (active !== playback) return
        try {
            val format = AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minBytes > 0)
            val track = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minBytes, sampleRate)).build()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED)
                val registered = playback.trackLock.withLock {
                    if (active !== playback) return@withLock false
                    playback.track = track
                    true
                }
                if (!registered) return
                writeReply(track, playback, sampleRate)
            } finally {
                playback.trackLock.withLock {
                    playback.underruns = runCatching { track.underrunCount }.getOrNull()
                    playback.track = null
                    silence(track)
                    track.release()
                }
            }
            main.post {
                if (active === playback) {
                    end(playback, "complete")
                    playback.complete()
                }
            }
        } catch (_: Exception) {
            main.post { fail(playback, "audio_failed", "Kokoro audio output stopped. Your answer remains in chat.") }
        }
    }

    private fun writeReply(track: AudioTrack, playback: Playback, sampleRate: Int) {
        var framesWritten = 0L
        var playing = false
        val primeFrames = minOf(track.bufferSizeInFrames, (sampleRate * 64 / 1_000).coerceAtLeast(1))
        while (active === playback) {
            val samples = playback.pcm.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (samples == null) {
                if (playback.synthesisDone && playback.pcm.isEmpty() && playedFrames(track, playback) >= framesWritten) return
                if (playing && playedFrames(track, playback) >= framesWritten) {
                    playback.trackLock.withLock { if (active === playback) track.pause() }
                    playing = false
                }
                continue
            }
            var offset = 0
            val startThreshold = minOf(primeFrames, samples.size)
            while (offset < samples.size) {
                val written = playback.trackLock.withLock {
                    if (active !== playback) return
                    if (!playing && offset == 0) track.setStartThresholdInFrames(startThreshold)
                    track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING).also { count ->
                        if (!playing && offset + count >= startThreshold) {
                            track.play()
                            playback.queuedAudio = true
                            playing = true
                        }
                    }
                }
                check(written >= 0)
                if (written == 0) {
                    Thread.sleep(POLL_MS)
                } else {
                    offset += written
                    playback.lastProgress = SystemClock.elapsedRealtime()
                }
                playedFrames(track, playback)
            }
            framesWritten += offset
        }
    }

    private fun loadModel(key: ModelKey): LoadedModel = VoiceNativeEngineGate.exclusive { loadModelExclusive(key) }

    private fun loadModelExclusive(key: ModelKey): LoadedModel {
        check(!closed)
        loaded?.takeIf { it.key == key }?.let { return it }
        releaseModel()
        check(models.acquireUse())
        val engineId = java.util.UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        AppLogRecorder.record("VoiceEngine", "kokoro_start · engineId=$engineId · threads=${key.threads}")
        try {
            val directory = models.modelDirectory()
            val lexicon = File(directory, key.lexicon)
            val words = lexicon.useLines { lines -> lines.map { it.substringBefore(' ') }.toHashSet() }
            val engine = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(directory, KokoroModelPack.MODEL).absolutePath,
                            voices = File(directory, KokoroModelPack.VOICES).absolutePath,
                            tokens = File(directory, KokoroModelPack.TOKENS).absolutePath,
                            lexicon = lexicon.absolutePath
                        ),
                        numThreads = key.threads
                    ),
                    maxNumSentences = 1
                )
            )
            AppLogRecorder.record("VoiceEngine", "kokoro_ready · engineId=$engineId · threads=${key.threads} · durationMs=${SystemClock.elapsedRealtime() - started}")
            return LoadedModel(key, engine, words).also { loaded = it }
        } finally {
            if (loaded == null) AppLogRecorder.record("VoiceEngine", "kokoro_failure · engineId=$engineId · durationMs=${SystemClock.elapsedRealtime() - started}", "W")
            if (loaded == null) models.releaseUse()
        }
    }

    private fun playedFrames(track: AudioTrack, playback: Playback): Long {
        val frames = Integer.toUnsignedLong(track.playbackHeadPosition)
        if (frames > 0 && !playback.firstFrame) {
            playback.firstFrame = true
            timing("first_audio", playback)
        }
        return frames
    }

    private fun timing(stage: String, playback: Playback, detail: String = "") {
        val fields = if (detail.isBlank()) "" else " · $detail"
        AppLogRecorder.record(
            "VoiceTiming",
            "kokoro_$stage · playbackId=${playback.id} · voice=${playback.voice.name} · threads=${playback.key.threads} · durationMs=${SystemClock.elapsedRealtime() - playback.startedAt}$fields"
        )
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

    override fun releaseWarmModel() {
        if (closed) return
        warmupGeneration++
        warmupRequest = null
        synthesis.execute(::releaseModel)
    }

    private fun end(playback: Playback, reason: String) {
        if (active !== playback) return
        active = null
        main.removeCallbacks(playback.watchdog)
        playback.segments.clear()
        playback.pcm.clear()
        val underruns = playback.trackLock.withLock {
            playback.track?.let { track ->
                playback.underruns = runCatching { track.underrunCount }.getOrNull()
                silence(track)
            }
            playback.underruns
        }
        runCatching { audioManager?.abandonAudioFocusRequest(playback.focus) }
        timing("terminal", playback, "reason=$reason · textChars=${playback.inputChars} · underruns=${underruns ?: "unmeasured"}")
    }

    private fun fail(playback: Playback, reason: String, message: String, canFallback: Boolean = !playback.queuedAudio) {
        if (active !== playback) return
        end(playback, reason)
        playback.error(message, canFallback)
    }

    override fun stop() {
        warmupGeneration++
        warmupRequest = null
        active?.let { end(it, "interrupted") }
    }

    override fun close() {
        if (closed) return
        active?.let { end(it, "session_closed") }
        releaseWarmModel()
        closed = true
        application.unregisterComponentCallbacks(memoryCallbacks)
        synthesis.shutdown()
        output.shutdown()
    }

    private companion object {
        const val MAX_REPLY_CHARS = 12_100
        const val MAX_SEGMENTS = MAX_REPLY_CHARS
        const val MAX_PCM_BATCHES = 3
        const val POLL_MS = 12L
        const val INPUT_WAIT_MS = 100L
        const val WATCHDOG_MS = 30_000L
    }
}
