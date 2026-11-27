package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.readiness.ChatReadinessCoordinator
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessSettings
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class VoiceRuntime(
    val system: AndroidVoiceAudio,
    val recognition: MoonshineVoiceRecognizer,
    val kokoro: KokoroOutput?,
    val audio: VoicePluginAudio,
    val session: VoiceSession,
    background: VoiceBackgroundPlayback
) {
    init {
        session.automaticAudioAvailable = { !background.playingElsewhere(this) }
    }

    val tuningConfiguration = VoiceTuningConfiguration(audio, kokoro, session)
    val playback = VoiceMessagePlayback()
    val replyProjection = VoiceReplyProjectionCache()
    val activeMessage = mutableStateOf<VoiceMessageIdentity?>(null)
    val manualMessage = mutableStateOf(false)
    val refreshMutedBaseline = mutableStateOf(false)
}

/** Navigation owns native runtimes; a recreated composition only attaches a presentation. */
@HiltViewModel
internal class VoiceRuntimeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val readiness: Lazy<ChatReadinessCoordinator>,
    val background: VoiceBackgroundPlayback
) : ViewModel() {
    suspend fun prepareProfile(profile: PlatformV2, settings: ReadinessSettings, roomId: Int, privateRoom: Boolean) {
        withContext(Dispatchers.IO) {
            readiness.get().prepareVoiceProfile(profile, settings, ConversationPrivacyContext(roomId, privateRoom))
        }
    }

    private var owner: ChatViewModel? = null
    private var runtime: VoiceRuntime? = null
    private var runtimeStores: VoiceModelStores? = null
    private var modelChangePending = false
    private var eviction: Job? = null

    fun acquire(model: ChatViewModel, stores: VoiceModelStores, restore: (VoiceSession) -> Unit): VoiceRuntime {
        runtime?.takeIf { modelChangePending }?.let { return it }
        runtime?.takeIf { owner === model && it.session.state.phase != VoicePhase.CLOSED }?.let {
            check(runtimeStores?.kokoro === stores.kokoro) { "The previous speech model must retire before a replacement is acquired." }
            return it
        }
        runtime?.let(background::releaseScreenOwner)
        owner = model
        runtimeStores = stores
        val system = AndroidVoiceAudio(context)
        val privacy = {
            val room = model.chatRoom.value
            // A visible runtime may be allocated before Room finishes loading. No
            // online audio is admitted until its saved conversation is known.
            ConversationPrivacyContext(room.id.takeIf { it > 0 }, room.id <= 0 || room.privacyModeLocked)
        }
        system.bindConversationPrivacy(privacy)
        val recognition = MoonshineVoiceRecognizer(context, stores.moonshine)
        val kokoro = createKokoroVoiceOutput(context, stores.kokoro, system::playbackAttributes, { system.speechRate }, privacy)
        val audio = VoicePluginAudio(system, recognition, kokoro, system::captureOfflineSpeech, { system.releaseRecognition() })
        var observed: VoiceRuntime? = null
        var checkpoint: String? = null
        val session = VoiceSession(audio, submit = model::submitVoiceTranscript, awaitsAdmission = true, submittedRequestId = { model.voiceAdmission.value?.requestId }, changed = { state ->
            observed?.let { background.observe(it, model.chatRoom.value.id, state) }
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
        return VoiceRuntime(system, recognition, kokoro, audio, session, background).also {
            observed = it
            runtime = it
        }
    }

    fun resume() {
        runtime?.let(background::attach)
        eviction?.cancel()
        eviction = null
    }

    /** Model changes are explicit settings actions. Never block main or the native engine gate. */
    suspend fun retireForModelChange(): Boolean {
        val previous = runtime ?: return true
        modelChangePending = true
        eviction?.cancel()
        eviction = null
        if (runCatching { previous.session.close() }.isFailure) return false
        val retired = withContext(Dispatchers.IO) {
            withTimeoutOrNull(30_000) {
                while (previous.kokoro?.awaitClosed(500) == false) delay(25)
                true
            } ?: false
        }
        if (retired && runtime === previous) {
            runtime = null
            runtimeStores = null
            owner = null
            modelChangePending = false
        }
        return retired
    }

    /** Capture/playback are paused by the session immediately; only loaded models receive a grace period. */
    fun pause(expected: VoiceRuntime? = null) {
        if (expected != null && runtime !== expected) return
        runtime?.let(background::detach)
        if (eviction?.isActive == true) return
        eviction = viewModelScope.launch {
            delay(30_000)
            runtime?.takeUnless(background::owns)?.kokoro?.releaseWarmModel()
            runtime?.recognition?.releaseWarmModel()
        }
    }

    override fun onCleared() {
        eviction?.cancel()
        runtime?.let(background::releaseScreenOwner)
        runtime = null
        runtimeStores = null
        owner = null
    }
}
