package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.voice.AndroidVoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessagePlayback
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginAudio
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import dev.chungjungsoo.gptmobile.data.voice.shouldCheckpointDraft
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class VoiceRuntime(
    val system: AndroidVoiceAudio,
    val recognition: MoonshineVoiceRecognizer,
    val kokoro: KokoroOutput?,
    val audio: VoicePluginAudio,
    val session: VoiceSession
) {
    val tuningConfiguration = VoiceTuningConfiguration(audio, kokoro, session)
    val playback = VoiceMessagePlayback()
    val replyProjection = VoiceReplyProjectionCache()
    val activeMessage = mutableStateOf<VoiceMessageIdentity?>(null)
    val manualMessage = mutableStateOf(false)
    val refreshMutedBaseline = mutableStateOf(false)
}

/** Navigation owns native runtimes; a recreated composition only attaches a presentation. */
@HiltViewModel
internal class VoiceRuntimeViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private var owner: ChatViewModel? = null
    private var runtime: VoiceRuntime? = null
    private var eviction: Job? = null

    fun acquire(model: ChatViewModel, stores: VoiceModelStores, restore: (VoiceSession) -> Unit): VoiceRuntime {
        runtime?.takeIf { owner === model && it.session.state.phase != VoicePhase.CLOSED }?.let { return it }
        runtime?.session?.close()
        owner = model
        val system = AndroidVoiceAudio(context)
        val recognition = MoonshineVoiceRecognizer(context, stores.moonshine)
        val kokoro = createKokoroVoiceOutput(context, stores.kokoro, system::playbackAttributes) { system.speechRate }
        val audio = VoicePluginAudio(system, recognition, kokoro, system::speakOffline, system::releaseRecognition)
        var checkpoint: String? = null
        val session = VoiceSession(audio, submit = model::submitVoiceTranscript, awaitsAdmission = true, submittedRequestId = { model.voiceAdmission.value?.requestId }, changed = { state ->
            val admission = model.voiceAdmission.value
            if (!state.shouldCheckpointDraft(admission?.requestId, admission?.stage)) {
                checkpoint = null
            } else if (state.transcript != checkpoint) {
                checkpoint = state.transcript
                model.checkpointVoiceDraft(state.transcript)
            }
        })
        session.submissionRejection = { model.voiceSubmissionRejection }
        session.synthesisFeedback = { kokoro?.synthesisFeedback?.value }
        restore(session)
        return VoiceRuntime(system, recognition, kokoro, audio, session).also { runtime = it }
    }

    fun resume() {
        eviction?.cancel()
        eviction = null
    }

    /** Capture/playback are paused by the session immediately; only loaded models receive a grace period. */
    fun pause() {
        if (eviction?.isActive == true) return
        eviction = viewModelScope.launch {
            delay(30_000)
            runtime?.kokoro?.releaseWarmModel()
            runtime?.recognition?.releaseWarmModel()
        }
    }

    override fun onCleared() {
        eviction?.cancel()
        runtime?.session?.close()
        runtime = null
        owner = null
    }
}
