package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public sentences synthesized with CMU Flite slt and rms, mono G.711 mu-law at 16 kHz. */
internal val voiceBenchRecordings = mapOf("ready" to "The voice is ready for your next question.", "weather" to "Please check the weather and read the answer out loud.")

internal val voiceBenchRecordingHashes = mapOf(
    "ready" to "c413870702dcb1391947078bb63579ce74f55da0e9b9d2ff2c7afedfd6af5bf4",
    "weather" to "f5112c380389c8ec4c0e6be80e0ef96db191500b68c907a72986948f112d996a"
)

internal fun voiceBenchFixtureVerified(id: String, audio: ByteArray): Boolean =
    java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) } == voiceBenchRecordingHashes[id]

internal fun decodeVoiceBenchSample(value: Byte): Float {
    val code = value.toInt().inv() and 255
    val magnitude = (((code and 15) * 8 + 132) shl ((code shr 4) and 7)) - 132
    return (if (code and 128 == 0) magnitude else -magnitude) / 32768f
}

internal fun voiceBenchWords(text: String) = Regex("[\\p{L}\\p{M}\\p{N}']+").findAll(text.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()

internal fun voiceBenchWordErrors(expected: List<String>, heard: List<String>): Int {
    var previous = IntArray(heard.size + 1) { it }
    expected.forEachIndexed { row, word ->
        val next = IntArray(heard.size + 1)
        next[0] = row + 1
        heard.forEachIndexed { column, actual ->
            next[column + 1] = minOf(previous[column + 1] + 1, next[column] + 1, previous[column] + if (word == actual) 0 else 1)
        }
        previous = next
    }
    return previous.last()
}

internal suspend fun voiceBenchMoonshine(context: Context, style: VoiceListeningStyle, active: () -> Boolean, cleanupFailed: (Any) -> Unit, checkpoint: (List<VoiceBenchObservation>) -> Unit): List<VoiceBenchObservation> {
    val models = LocalVoiceModelStore(context)
    models.refresh(false)
    if (!models.acquireUse()) return listOf(VoiceBenchObservation.unavailable(style.name, "MOONSHINE_ENGLISH_PACK_MISSING"))
    val decoder = MoonshineStreamDecoder()
    var retired = false
    try {
        return VoiceNativeEngineGate.exclusive {
            decoder.configure(style)
            val rows = mutableListOf<VoiceBenchObservation>()
            for ((id, reference) in voiceBenchRecordings) {
                val data = context.assets.open("voice-benchmark/$id.mulaw").use { it.readBytes() }
                if (!voiceBenchFixtureVerified(id, data)) {
                    rows += VoiceBenchObservation(id, VoiceBenchStatus.FAILED, "FIXTURE_DIGEST_MISMATCH")
                    checkpoint(rows)
                    continue
                }
                val start = System.nanoTime()
                var firstPartialMs: Long? = null
                var final = ""
                decoder.start(models.modelDirectory()) { event ->
                    when (event) {
                        is LocalSpeechEvent.Partial -> if (firstPartialMs == null && event.text.isNotBlank()) firstPartialMs = elapsedVoiceBenchMs(start)
                        is LocalSpeechEvent.Final -> final = event.text
                        else -> Unit
                    }
                }
                try {
                    val prepared = elapsedVoiceBenchMs(start)
                    var offset = 0
                    while (offset < data.size + 16_000) {
                        if (!active()) throw CancellationException("Voice benchmark stopped")
                        val count = minOf(640, data.size + 16_000 - offset)
                        decoder.accept(FloatArray(count) { i -> data.getOrNull(offset + i)?.let(::decodeVoiceBenchSample) ?: 0f })
                        offset += count
                    }
                    decoder.finish()
                    val words = voiceBenchWords(reference)
                    val errors = voiceBenchWordErrors(words, voiceBenchWords(final))
                    rows += VoiceBenchObservation(
                        id,
                        if (final.isBlank()) VoiceBenchStatus.FAILED else VoiceBenchStatus.MEASURED,
                        reason = if (final.isBlank()) "NO_FINAL_TRANSCRIPT" else null,
                        metrics = buildMap {
                            put("referenceWords", words.size.toDouble())
                            put("wordErrors", errors.toDouble())
                            put("wordErrorRate", errors.toDouble() / words.size)
                            put("audioMs", data.size / 16.0)
                            put("prepareMs", prepared.toDouble())
                            put("finalMs", elapsedVoiceBenchMs(start).toDouble())
                            firstPartialMs?.let { put("firstPartialMs", it.toDouble()) }
                        }
                    )
                } finally {
                    decoder.releaseStream()
                }
                checkpoint(rows)
            }
            rows
        }
    } finally {
        try {
            VoiceNativeEngineGate.exclusive { decoder.close() }
            retired = true
        } finally {
            if (retired) models.releaseUse() else cleanupFailed(listOf(decoder, models))
        }
    }
}

/** Query only Android's local recognizer. Capability is observed independently of transcription accuracy. */
internal suspend fun voiceBenchSystemRecognition(context: Context, active: () -> Boolean): List<VoiceBenchObservation> = withContext(Dispatchers.Main.immediate) {
    val service = AndroidRecognitionService(context)
    if (!service.available(true)) return@withContext listOf(VoiceBenchObservation.unavailable("android", "NO_ON_DEVICE_RECOGNIZER"))
    SpeechLanguages.synthesis.map { language ->
        if (!active()) throw CancellationException("Voice benchmark stopped")
        // A timed-out support request must not remain outstanding on the recognizer for the next locale.
        val recognizer = service.create(true)
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            val qualification = withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    service.checkSupport(recognizer, intent, true) { if (continuation.isActive) continuation.resume(it) }
                }
            }
            val status = when (qualification?.status) {
                RecognitionSupportStatus.SUPPORTED -> VoiceBenchStatus.MEASURED
                RecognitionSupportStatus.FAILED -> VoiceBenchStatus.FAILED
                else -> VoiceBenchStatus.UNAVAILABLE
            }
            VoiceBenchObservation(language.tag, status, qualification?.status?.name ?: "CAPABILITY_QUERY_TIMEOUT")
        } finally {
            recognizer.destroy()
        }
    }
}
