package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.audio.VoiceSessionCoordinator
import java.util.Locale

/** Explicit opt-in, foreground voice loop. Speech service networking is chosen by the user. */
@Composable
internal fun VoiceChatControls(generating: Boolean, answer: String, answerId: Int?, onSend: (String) -> Unit, onInterrupt: () -> Unit) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val owner = LocalLifecycleOwner.current
    val send by rememberUpdatedState(onSend)
    val interrupt by rememberUpdatedState(onInterrupt)
    val coordinator = remember { VoiceSessionCoordinator() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var enabled by remember { mutableStateOf(false) }
    var useSystemService by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var awaitingAnswer by remember { mutableStateOf(false) }
    var lastAnswerId by remember { mutableStateOf<Int?>(null) }
    val beginListening: () -> Unit = {
        if (!enabled) {
            Unit
        } else {
            tts?.stop()
            recognizer?.cancel()
            recognizer?.destroy()
            val available = if (useSystemService) SpeechRecognizer.isRecognitionAvailable(context) else SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if (!available) {
                status = resources.getString(R.string.voice_unavailable)
                enabled = false
            } else {
                val active = if (useSystemService) SpeechRecognizer.createSpeechRecognizer(context) else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = active
                active.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        coordinator.startListening()
                        status = resources.getString(R.string.voice_listening)
                    }
                    override fun onBeginningOfSpeech() {
                        coordinator.onSpeechDetected(1f)
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        coordinator.onSpeechDetected((rmsdB / 12f).coerceIn(0f, 1f))
                    }
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        coordinator.onSilenceDetected()
                        status = resources.getString(R.string.voice_transcribing)
                    }
                    override fun onError(error: Int) {
                        coordinator.onError(resources.getString(R.string.voice_recognition_error, error))
                        status = resources.getString(R.string.voice_recognition_error, error)
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (enabled && text.isNotBlank()) {
                            coordinator.onTranscriptionComplete()
                            awaitingAnswer = true
                            status = resources.getString(R.string.voice_generating)
                            send(text)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                active.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !useSystemService)
                    }
                )
            }
        }
    }
    val listen by rememberUpdatedState(beginListening)
    fun stop() {
        enabled = false
        awaitingAnswer = false
        recognizer?.cancel()
        tts?.stop()
        coordinator.stopSession()
        status = resources.getString(R.string.voice_stopped)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        enabled = allowed
        if (allowed) listen() else status = resources.getString(R.string.voice_denied)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            stop()
            recognizer?.destroy()
            owner.lifecycle.removeObserver(observer)
            handler.removeCallbacksAndMessages(null)
        }
    }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0) handler.post { stop() } }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop()
            status = resources.getString(R.string.voice_audio_busy)
            return@DisposableEffect onDispose { }
        }
        var speechReference: TextToSpeech? = null
        val speech = TextToSpeech(context) { result ->
            val engine = speechReference
            val localVoice = engine?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
            ttsReady = result == TextToSpeech.SUCCESS && (useSystemService || localVoice != null)
            if (!useSystemService && localVoice != null) engine.voice = localVoice
            if (!ttsReady) status = resources.getString(R.string.voice_offline_playback_missing)
        }
        speechReference = speech
        tts = speech
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handler.post {
                    coordinator.onPlaybackStart()
                    status = resources.getString(R.string.voice_speaking)
                }
            }
            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (enabled) {
                        coordinator.onPlaybackComplete()
                        listen()
                    }
                }
            }

            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                handler.post { status = resources.getString(R.string.voice_playback_failed) }
            }
        })
        onDispose {
            ttsReady = false
            tts = null
            speech.stop()
            speech.shutdown()
            audio.abandonAudioFocusRequest(focus)
        }
    }
    LaunchedEffect(generating, answer, answerId, enabled, ttsReady) {
        if (enabled && awaitingAnswer && !generating && answer.isNotBlank() && answerId != lastAnswerId && ttsReady) {
            lastAnswerId = answerId
            awaitingAnswer = false
            tts?.speak(answer.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, "chat-$answerId")
        }
    }
    Column {
        Row {
            TextButton(onClick = {
                if (!enabled) {
                    lastAnswerId = answerId
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        enabled = true
                        listen()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    awaitingAnswer = false
                    interrupt()
                    listen()
                }
            }) { Text(if (enabled) resources.getString(R.string.voice_interrupt) else resources.getString(R.string.voice_conversation)) }
            if (enabled) TextButton(onClick = { stop() }) { Text(resources.getString(R.string.voice_stop)) }
        }
        if (enabled || status.isNotBlank()) {
            Row {
                Checkbox(checked = useSystemService, enabled = !enabled, onCheckedChange = { useSystemService = it })
                Text(resources.getString(R.string.voice_system_service), Modifier.weight(1f))
            }
            Text(status)
        }
    }
}
