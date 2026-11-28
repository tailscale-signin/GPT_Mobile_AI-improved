package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only fixed synthetic speech is written here. A provider must consume that file before it can receive a score. */
internal suspend fun voiceBenchLoopback(context: Context, profile: SpeechLanguage, pcm: ByteArray, active: () -> Boolean, interrupt: AtomicReference<(() -> Unit)?>): VoiceBenchObservation {
    val label = "android-recognition-loopback-${profile.tag}"
    val scope = "CONSUMED_SYNTHETIC_FIXTURE_TRANSCRIPTION_NOT_HUMAN_LISTENING_OR_ACOUSTIC_ALIGNMENT"
    fun unavailable(reason: String, metrics: Map<String, Double> = emptyMap()) = VoiceBenchObservation(label, VoiceBenchStatus.UNAVAILABLE, reason, metrics, scope)
    if (Build.VERSION.SDK_INT < 33) return unavailable("EXTERNAL_AUDIO_REQUIRES_API_33")
    if (pcm.isEmpty() || pcm.size > 1_440_000) return unavailable("FIXED_AUDIO_UNAVAILABLE")
    val file = File.createTempFile("voice-fixture-", ".pcm", context.cacheDir)
    try {
        file.outputStream().use { output ->
            output.write(pcm)
            output.write(ByteArray(VOICE_PCM_SAMPLE_RATE * 2))
        }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            return withContext(Dispatchers.Main.immediate) {
                if (!active()) throw CancellationException("Benchmark stopped")
                val service = AndroidRecognitionService(context)
                if (!service.available(true)) return@withContext unavailable("NO_ON_DEVICE_RECOGNIZER")
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, profile.tag)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VOICE_PCM_SAMPLE_RATE)
                val support = voiceBenchRecognitionSupport(active, interrupt) { voiceBenchRecognitionBinding(service, intent) }
                if (support.qualification?.status != RecognitionSupportStatus.SUPPORTED) return@withContext unavailable(requireNotNull(support.reason), support.metrics)
                // Support-query activity must never count as consumption by the transcription session.
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                val recognizer = service.create(true)
                val final = CompletableDeferred<Pair<Int?, String>>()
                val stop = {
                    final.cancel()
                    Unit
                }
                interrupt.set(stop)
                try {
                    if (!active()) throw CancellationException("Benchmark stopped")
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onError(error: Int) {
                            final.complete(error to "")
                        }
                        override fun onResults(results: Bundle?) {
                            final.complete(null to results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                        }
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    val start = System.nanoTime()
                    recognizer.startListening(intent)
                    val result = withTimeoutOrNull(10_000L) { final.await() }
                    val consumed = Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR)
                    val supportMetrics = support.metrics + ("sourceBytesRead" to consumed.toDouble())
                    if (consumed < pcm.size) return@withContext unavailable("INJECTED_FIXTURE_CONSUMPTION_UNCONFIRMED", supportMetrics)
                    if (result == null || result.first != null || result.second.isBlank()) return@withContext VoiceBenchObservation(label, VoiceBenchStatus.FAILED, result?.first?.let { "ANDROID_RECOGNITION_ERROR_$it" } ?: "NO_FINAL_TRANSCRIPT", supportMetrics, scope)
                    val byCharacter = profile.dictionaryLanguage in setOf("ja", "zh")
                    fun units(text: String): List<String> = if (byCharacter) text.filter(Char::isLetterOrDigit).map(Char::toString) else voiceBenchWords(text)
                    val reference = units(profile.preview)
                    val errors = voiceBenchWordErrors(reference, units(result.second))
                    val metric = if (byCharacter) "characterErrorRate" else "wordErrorRate"
                    VoiceBenchObservation(label, VoiceBenchStatus.MEASURED, metrics = supportMetrics + mapOf(metric to errors.toDouble() / reference.size, "referenceUnits" to reference.size.toDouble(), "editErrors" to errors.toDouble(), "elapsedMs" to elapsedVoiceBenchMs(start).toDouble()), measurementScope = scope)
                } finally {
                    final.cancel()
                    interrupt.compareAndSet(stop, null)
                    try {
                        recognizer.cancel()
                    } finally {
                        recognizer.destroy()
                    }
                }
            }
        }
    } finally {
        file.delete()
    }
}
