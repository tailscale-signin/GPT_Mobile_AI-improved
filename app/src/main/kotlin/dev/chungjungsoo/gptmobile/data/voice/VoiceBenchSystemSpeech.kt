package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Measure the same default Android synthesizer used by AndroidVoiceAudio, using installed offline voices. */
internal suspend fun voiceBenchSystemSpeech(context: Context, active: () -> Boolean, interrupt: AtomicReference<(() -> Unit)?>, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
    val init = CompletableDeferred<Int>()
    val synthesizer = TextToSpeech(context) { init.complete(it) }
    val current = AtomicReference<SystemSpeechUtterance?>()
    val observations = mutableListOf<VoiceBenchObservation>()
    val file = File.createTempFile("voice-suite-", ".wav", context.cacheDir)
    interrupt.set {
        init.cancel()
        current.get()?.done?.cancel()
        synthesizer.stop()
        Unit
    }
    try {
        if (!active()) throw CancellationException("Voice benchmark stopped")
        if (withTimeoutOrNull(5_000L) { init.await() } != TextToSpeech.SUCCESS) return listOf(VoiceBenchObservation.unavailable("android-tts", "SYSTEM_TTS_UNAVAILABLE"))
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun observation(id: String?) = current.get()?.takeIf { it.id == id }
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                observation(utteranceId)?.done?.complete(true)
            }

            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                observation(utteranceId)?.done?.complete(false)
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                observation(utteranceId)?.done?.complete(false)
            }
            override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                if (audio != null && audio.isNotEmpty()) observation(utteranceId)?.let { it.first.compareAndSet(-1L, elapsedVoiceBenchMs(it.started)) }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                observation(utteranceId)?.ranges?.incrementAndGet()
            }
        })
        val installed = synthesizer.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }.sortedBy { it.name }
        val languages = installed.map { it.locale.toLanguageTag() }.toSet()
        val selected = installed.mapNotNull { voice -> SpeechLanguages.forLocale(voice.locale.toLanguageTag())?.let { it to voice } }
        for ((index, pair) in selected.withIndex()) {
            if (!active()) throw CancellationException("Voice benchmark stopped")
            val (profile, voice) = pair
            val label = "${voice.locale.toLanguageTag()}-${index + 1}"
            val utterance = SystemSpeechUtterance(label)
            current.set(utterance)
            val row = if (synthesizer.setVoice(voice) != TextToSpeech.SUCCESS || synthesizer.voice?.name != voice.name || synthesizer.voice?.isNetworkConnectionRequired != false) {
                VoiceBenchObservation.unavailable(label, "OFFLINE_VOICE_UNAVAILABLE")
            } else {
                file.outputStream().use { }
                val started = synthesizer.synthesizeToFile(profile.preview, Bundle(), file, utterance.id) == TextToSpeech.SUCCESS
                val done = if (started) withTimeoutOrNull(15_000L) { utterance.done.await() } else false
                if (done == null) synthesizer.stop()
                val audio = if (done == true) voiceBenchWav(file) else null
                val reason = when {
                    done == null -> "SYSTEM_TTS_TIMEOUT"
                    done != true -> "SYSTEM_TTS_FAILED"
                    audio == null -> "INVALID_WAV"
                    audio.signal.nonSilent == 0L -> "SILENT_WAV"
                    audio.signal.clipped.toDouble() / audio.signal.samples > .01 -> "CLIPPED_WAV"
                    else -> null
                }
                VoiceBenchObservation(
                    label,
                    if (reason == null) VoiceBenchStatus.MEASURED else VoiceBenchStatus.FAILED,
                    reason,
                    buildMap {
                        put("elapsedMs", elapsedVoiceBenchMs(utterance.started).toDouble())
                        audio?.let {
                            put("audioMs", it.durationMs)
                            put("sampleRate", it.sampleRate.toDouble())
                            put("rms", it.signal.rms)
                            put("clippedSamples", it.signal.clipped.toDouble())
                        }
                        utterance.first.get().takeIf { it >= 0 }?.let { put("firstAudioCallbackMs", it.toDouble()) }
                        put("rangeCallbacks", utterance.ranges.get().toDouble())
                    }
                )
            }
            observations += row
            checkpoint(observations)
        }
        SpeechLanguages.synthesis.filter { profile -> languages.none { it.equals(profile.tag, true) || !profile.tag.contains('-') && it.substringBefore('-') == profile.tag } }.forEach {
            observations += VoiceBenchObservation.unavailable(it.tag, "NO_INSTALLED_OFFLINE_SYSTEM_VOICE_FOR_LOCALE")
        }
        return observations
    } finally {
        interrupt.set(null)
        current.set(null)
        try {
            synthesizer.stop()
        } finally {
            try {
                synthesizer.shutdown()
            } finally {
                file.delete()
            }
        }
    }
}

private class SystemSpeechUtterance(val id: String, val started: Long = System.nanoTime()) {
    val done = CompletableDeferred<Boolean>()
    val first = AtomicLong(-1L)
    val ranges = AtomicInteger()
}

/** Walk RIFF chunks including odd-byte padding. File bytes alone do not establish an audio duration. */
internal data class VoiceBenchWave(val durationMs: Double, val sampleRate: Int, val signal: VoicePcmSignal)

internal fun voiceBenchWav(file: File): VoiceBenchWave? {
    if (file.length() !in 44L..16_777_216L) return null
    return RandomAccessFile(file, "r").use { input ->
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun u32() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun u16() = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
        if (tag() != "RIFF") return@use null
        val limit = u32() + 8
        if (limit !in 44..input.length() || tag() != "WAVE") return@use null
        var rate = 0
        var alignment = 0
        var bytes = 0L
        var bits = 0
        var encoding = 0
        val chunks = mutableListOf<Pair<Long, Long>>()
        while (input.filePointer + 8 <= limit) {
            val type = tag()
            val length = u32()
            val start = input.filePointer
            if (start + length > limit) return@use null
            when (type) {
                "fmt " -> {
                    if (length < 16 || rate != 0) return@use null
                    encoding = u16()
                    if (encoding !in setOf(1, 3)) return@use null
                    val channels = u16()
                    rate = u32().toInt()
                    val perSecond = u32()
                    alignment = u16()
                    bits = u16()
                    if (encoding == 3 && bits != 32) return@use null
                    if (channels !in 1..8 || rate !in 8_000..192_000 || bits !in setOf(8, 16, 24, 32) || alignment != channels * bits / 8 || perSecond != rate.toLong() * alignment) return@use null
                }
                "data" -> {
                    if (chunks.size >= 256) return@use null
                    chunks += start to length
                    bytes += length
                }
            }
            input.seek(start + length + length % 2)
        }
        if (alignment <= 0 || bytes <= 0 || chunks.any { it.second % alignment != 0L }) return@use null
        val signal = VoicePcmSignalMeasurement()
        val width = bits / 8
        val block = ByteArray(2048 * width)
        for ((offset, size) in chunks) {
            input.seek(offset)
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                input.readFully(block, 0, count)
                val buffer = ByteBuffer.wrap(block, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                val samples = FloatArray(count / width) {
                    when {
                        encoding == 3 -> buffer.float
                        bits == 8 -> ((buffer.get().toInt() and 255) - 128) / 128f
                        bits == 16 -> buffer.short / 32768f
                        bits == 24 -> ((buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)) / 8388608f
                        else -> buffer.int / 2147483648f
                    }
                }
                if (samples.any { !it.isFinite() }) return@use null
                signal.accept(samples)
                remaining -= count
            }
        }
        VoiceBenchWave(bytes / alignment * 1000.0 / rate, rate, signal.snapshot() ?: return@use null)
    }
}
