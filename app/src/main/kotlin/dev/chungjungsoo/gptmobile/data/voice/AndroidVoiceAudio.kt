package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

/** Created only in an enabled, visible voice session. Audio is never recorded to an app file. */
class AndroidVoiceAudio internal constructor(
    private val context: Context,
    private val speechFactory: (Context, TextToSpeech.OnInitListener) -> TextToSpeech
) : VoiceAudio {
    constructor(context: Context) : this(context, { owner, listener -> TextToSpeech(owner, listener) })

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var speechGeneration = 0L
    private var closed = false
    private var generation = 0L
    private var pendingSpeech: (() -> Unit)? = null
    private var playbackError: ((String) -> Unit)? = null

    override fun listen(listener: VoiceAudio.Listener) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        if (closed) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error("Microphone permission is needed for voice chat.")
            return
        }
        val ticket = generation
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.error("Speech recognition is unavailable. Install or enable a speech service in Android settings.")
            return
        }
        try {
            val engine = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = engine
            fun active() = !closed && ticket == generation
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active()) listener.ready()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (active()) listener.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    if (active()) listener.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onResults(results: Bundle?) {
                    if (active()) listener.final(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    if (!active()) return
                    listener.error(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t catch that. Tap the wave to listen again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice chat."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Tap to try again."
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This speech language is unavailable. Review your Android speech settings."
                            else -> "The speech service stopped. Tap the wave to try again."
                        }
                    )
                }
            })
            engine.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        } catch (_: Exception) {
            listener.error("Could not open the microphone. Check Android permissions and your speech service.")
        }
    }

    override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
        stop()
        if (closed) return
        val ticket = generation
        playbackError = error
        val play: () -> Unit = play@{
            try {
                val engine = tts ?: return@play
                if (closed || ticket != generation) return@play
                val language = engine.setLanguage(Locale.getDefault())
                if (language < 0) {
                    error("A voice for this language is not installed. Captions are still available.")
                    return@play
                }
                val spoken = if (text.length > 12000) text.take(12000) + " The rest of this response is available in the chat." else text
                val chunks = speechChunks(spoken, minOf(3000, TextToSpeech.getMaxSpeechInputLength()))
                val finalId = "$ticket:${chunks.lastIndex}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == finalId) main.post { if (!closed && ticket == generation) complete() }
                    }

                    @Deprecated("Platform compatibility callback")
                    override fun onError(utteranceId: String?) {
                        main.post { if (!closed && ticket == generation) error("Could not play this response. Turn off the speaker to continue with captions.") }
                    }
                })
                for ((index, chunk) in chunks.withIndex()) {
                    if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$ticket:$index") == TextToSpeech.ERROR) {
                        error("Could not play this response. Captions are still available.")
                        break
                    }
                }
            } catch (_: Exception) {
                if (!closed && ticket == generation) error("The speech engine stopped. Turn off the speaker or try again.")
            }
        }
        if (speechReady) {
            play()
            return
        }
        pendingSpeech = play
        if (tts == null) {
            val initialization = ++speechGeneration
            try {
                tts = speechFactory(context) { status ->
                    main.post {
                        if (closed || initialization != speechGeneration) return@post
                        speechReady = status == TextToSpeech.SUCCESS
                        val pending = pendingSpeech
                        pendingSpeech = null
                        if (speechReady) {
                            pending?.invoke()
                        } else {
                            val failed = playbackError
                            playbackError = null
                            releaseSpeech()
                            failed?.invoke("Text-to-speech is unavailable. Turn off the speaker to continue with captions, or tap to try again.")
                        }
                    }
                }
            } catch (_: Exception) {
                pendingSpeech = null
                playbackError = null
                releaseSpeech()
                error("Could not start text-to-speech. Turn off the speaker to continue with captions, or tap to try again.")
            }
        }
    }

    private fun releaseSpeech() {
        speechGeneration++
        speechReady = false
        val previous = tts
        tts = null
        runCatching { previous?.shutdown() }
    }

    override fun stop() {
        generation++
        pendingSpeech = null
        playbackError = null
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        runCatching { tts?.stop() }
    }

    override fun close() {
        stop()
        closed = true
        releaseSpeech()
        main.removeCallbacksAndMessages(null)
    }
}
