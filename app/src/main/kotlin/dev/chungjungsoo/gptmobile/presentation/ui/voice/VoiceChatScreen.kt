package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechVoices
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage
import dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceDraftPreparation
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoiceMicrophoneAction
import dev.chungjungsoo.gptmobile.data.voice.VoicePauseDuration
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoicePreparation
import dev.chungjungsoo.gptmobile.data.voice.VoicePreparationPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction
import dev.chungjungsoo.gptmobile.data.voice.VoiceResponsePolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationLanguageStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPlaybackSelection
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationTarget
import dev.chungjungsoo.gptmobile.data.voice.microphoneAction
import dev.chungjungsoo.gptmobile.data.voice.selectTranslationPlayback
import dev.chungjungsoo.gptmobile.data.voice.shouldCheckpointDraft
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.chat.resolvePlatformModel
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatRevealPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun VoiceChatButton(onClick: (Offset) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    FloatingActionButton(
        onClick = { onClick(origin) },
        modifier = Modifier.size(60.dp).onGloballyPositioned { origin = it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)) }.testTag("home_voice_chat_icon"),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.primary
    ) { Icon(Icons.Rounded.GraphicEq, "Voice chat", Modifier.size(30.dp)) }
}

@Composable
internal fun VoiceEntryScreen(
    state: VoiceEntryState,
    origin: Offset,
    select: (String) -> Unit,
    start: () -> Unit,
    back: () -> Unit,
    settings: () -> Unit,
    modelPreparation: dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState? = null,
    recheckModel: () -> Unit = {},
    modelsReady: Boolean = true,
    showInstallation: Boolean = false,
    setup: @Composable () -> Unit = {}
) {
    NewChatRevealPanel(origin) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = back) { Icon(Icons.Rounded.Close, "Close voice chat", tint = MaterialTheme.colorScheme.onSurface) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceWave(VoiceState(), Modifier.fillMaxWidth().height(100.dp))
                Text(if (modelsReady) "Voice chat is ready" else "Talk with your AI", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (!state.loaded || state.creating) {
                    CircularProgressIndicator()
                } else if (!modelsReady) {
                    Column(
                        Modifier.widthIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Speak your message and listen to the reply.", textAlign = TextAlign.Center)
                        setup()
                    }
                } else {
                    Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceChoice("AI profile", state.selected, state.profiles.map { it.uid to it.name.ifBlank { it.model } }, select)
                    }
                    modelPreparation?.let { preparation ->
                        dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayModelPreparationContent(preparation, recheckModel)
                    }
                    if (state.profiles.isEmpty()) {
                        Button(onClick = settings) { Text("Set up an AI profile") }
                    } else {
                        Button(onClick = start, enabled = state.selected.isNotBlank(), modifier = Modifier.padding(top = 16.dp).testTag("voice_enable_start")) { Text("Start voice chat") }
                    }
                    if (showInstallation) setup()
                }
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            Text("Speech recognition runs on this phone. Spoken replies use your voice settings. Your selected AI profile receives the transcript.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun VoiceChatScreen(
    onEnd: () -> Unit,
    model: ChatViewModel = hiltViewModel(),
    voiceSettings: VoicePreferencesViewModel = hiltViewModel(),
    initialChatView: Boolean = false,
    onNavigateToLocalModels: () -> Unit = {},
    onReviewEvidenceAccess: () -> Unit = {},
    onProfileSettings: (String) -> Unit = {},
    onOpenConversation: (Int, Boolean) -> Unit = { _, _ -> },
    bottomInteractionContent: (@Composable () -> Unit)? = null,
    presentation: VoiceRoomPresentationViewModel = hiltViewModel()
) {
    val room by model.chatRoom.collectAsStateWithLifecycle()
    dev.chungjungsoo.gptmobile.presentation.common.PrivateConversationScreenProtection(room.id, room.privacyModeLocked)
    LaunchedEffect(room.id, room.createdAt, room.isTemporary) {
        presentation.rememberVoiceRoom(VoiceRoomIdentity.persistent(room.id, room.createdAt, room.isTemporary))
    }
    val roomError by model.roomLoadError.collectAsStateWithLifecycle()
    if (roomError != null) {
        BackHandler(onBack = onEnd)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Conversation unavailable", style = MaterialTheme.typography.headlineSmall)
            Text(roomError.orEmpty(), Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Center)
            Button(onClick = onEnd, modifier = Modifier.testTag("voice_missing_room_back")) { Text("Back to chats") }
        }
        return
    }
    val modelStores by voiceSettings.modelStores.collectAsStateWithLifecycle()
    val stores = modelStores
    if (stores == null) {
        BackHandler(onBack = onEnd)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    VoiceChatSessionScreen(
        onEnd,
        model,
        voiceSettings,
        stores,
        initialChatView,
        VoiceChatDestinations(onNavigateToLocalModels, onReviewEvidenceAccess, onProfileSettings, onOpenConversation),
        bottomInteractionContent
    )
}

/** Saveable State handles stay shared by the screen's effects and action callbacks. */
private class VoiceChatSessionSavedState(
    val listeningEntry: VoiceListeningEntry,
    val retainedMessageId: MutableIntState,
    val retainedRunId: MutableState<String>,
    val retainedRevision: MutableIntState,
    val chatView: MutableState<Boolean>,
    val retainedTranscript: MutableState<String>,
    val retainedTranslationSource: MutableState<String?>,
    val retainedReply: MutableState<String>,
    val retainedReview: MutableState<Boolean>,
    val retainedSubmittedRequestId: MutableState<String?>,
    val retainedMuted: MutableState<Boolean>,
    val listenAfterPermission: MutableState<Boolean>,
    val permissionRequestInFlight: MutableState<Boolean>,
    val captions: MutableState<Boolean>,
    val settingsOpen: MutableState<Boolean>,
    val draft: MutableState<String>,
    val language: MutableState<String>,
    val recognitionBackend: MutableState<VoiceRecognitionBackend>,
    val recognitionModel: MutableState<VoiceRecognitionModel>,
    val recognitionCompute: MutableState<VoiceRecognitionCompute>,
    val recognitionThreads: MutableIntState,
    val kokoroVoice: MutableState<KokoroVoice?>,
    val kokoroThreads: MutableIntState,
    val editBeforeSend: MutableState<Boolean>,
    val spokenReplies: MutableState<Boolean>,
    val buttonSounds: MutableState<Boolean>,
    val rate: MutableFloatState,
    val pause: MutableState<VoicePauseDuration>,
    val performance: MutableState<VoicePerformanceMode>,
    val speakWhileGenerating: MutableState<Boolean>,
    val kokoroBucket: MutableState<MediaPipeKokoroBucket>,
    val incrementalSpeech: MutableState<Boolean>,
    val nativeModelVariant: MutableState<KokoroModelVariant>,
    val preferencesInitialized: MutableState<Boolean>
)

@Composable
private fun rememberVoiceChatSessionState(initialChatView: Boolean, stores: VoiceModelStores): VoiceChatSessionSavedState = VoiceChatSessionSavedState(
    listeningEntry = rememberSaveable(saver = Saver<VoiceListeningEntry, Boolean>(save = { it.consumed }, restore = { VoiceListeningEntry(it) })) {
        VoiceListeningEntry()
    },
    retainedMessageId = rememberSaveable { mutableIntStateOf(0) },
    retainedRunId = rememberSaveable { mutableStateOf("") },
    retainedRevision = rememberSaveable { mutableIntStateOf(-1) },
    chatView = rememberSaveable { mutableStateOf(initialChatView) },
    retainedTranscript = rememberSaveable { mutableStateOf("") },
    retainedTranslationSource = rememberSaveable { mutableStateOf<String?>(null) },
    retainedReply = rememberSaveable { mutableStateOf("") },
    retainedReview = rememberSaveable { mutableStateOf(false) },
    retainedSubmittedRequestId = rememberSaveable { mutableStateOf<String?>(null) },
    retainedMuted = rememberSaveable { mutableStateOf(false) },
    listenAfterPermission = rememberSaveable { mutableStateOf(false) },
    permissionRequestInFlight = rememberSaveable { mutableStateOf(false) },
    captions = rememberSaveable { mutableStateOf(true) },
    settingsOpen = rememberSaveable { mutableStateOf(false) },
    draft = rememberSaveable { mutableStateOf("") },
    language = rememberSaveable { mutableStateOf(SpeechLanguages.recognitionDefault()) },
    recognitionBackend = rememberSaveable { mutableStateOf(VoiceRecognitionBackend.LOCAL_MOONSHINE) },
    recognitionModel = rememberSaveable { mutableStateOf(VoiceRecognitionModel.MOONSHINE_TINY) },
    recognitionCompute = rememberSaveable { mutableStateOf(VoiceRecognitionCompute.CPU) },
    recognitionThreads = rememberSaveable { mutableIntStateOf(0) },
    kokoroVoice = rememberSaveable { mutableStateOf<KokoroVoice?>(null) },
    kokoroThreads = rememberSaveable { mutableIntStateOf(VoiceCpuSchedulingPolicy.AUTO) },
    editBeforeSend = rememberSaveable { mutableStateOf(false) },
    spokenReplies = rememberSaveable { mutableStateOf(true) },
    buttonSounds = rememberSaveable { mutableStateOf(true) },
    rate = rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(STANDARD_SPEECH_RATE) },
    pause = rememberSaveable { mutableStateOf(VoicePauseDuration.NATURAL) },
    performance = rememberSaveable { mutableStateOf(VoicePerformanceMode.AUTO) },
    speakWhileGenerating = rememberSaveable { mutableStateOf(true) },
    kokoroBucket = rememberSaveable { mutableStateOf(MediaPipeKokoroBucket.AUTO) },
    incrementalSpeech = rememberSaveable { mutableStateOf(true) },
    nativeModelVariant = rememberSaveable { mutableStateOf(stores.kokoroVariant) },
    preferencesInitialized = rememberSaveable { mutableStateOf(false) }
)

private class VoiceChatSessionTransientState(
    val feedback: VoiceButtonFeedback,
    val profilePreflightReady: MutableState<Boolean>,
    val profilePreflightError: MutableState<String?>,
    val playbackPrimed: MutableState<Boolean>,
    val playbackTick: MutableIntState,
    val translationReplayGeneration: MutableIntState,
    val preferencesApplied: MutableState<Boolean>,
    val permission: MutableState<Boolean>,
    val synthesis: MutableState<VoiceSynthesisPreferences>,
    val translationMode: MutableState<Boolean>,
    val translationPair: MutableState<VoiceTranslationPair>,
    val translationSetupNotice: MutableState<String?>,
    val translationFactories: MutableState<VoiceTranslationFactoryCache>,
    val microphoneCuePrepared: MutableState<Boolean>,
    val modelChangeRunning: MutableState<Boolean>,
    val modelChangeError: MutableState<String?>,
    val tuning: MutableState<VoiceTuningPreferences>,
    val route: MutableState<String>,
    val routeOpen: MutableState<Boolean>,
    val remaining: MutableIntState
)

@Composable
private fun rememberVoiceChatTransientState(
    context: Context,
    model: ChatViewModel,
    roomId: Int,
    privateRoom: Boolean,
    selectedProfile: PlatformV2?,
    runtime: VoiceRuntime
): VoiceChatSessionTransientState {
    val profilePreflightReady = remember(model, roomId, privateRoom, selectedProfile) { mutableStateOf(false) }
    val profilePreflightError = remember(model, roomId, privateRoom, selectedProfile) { mutableStateOf<String?>(null) }
    val playbackPrimed = remember(runtime) { mutableStateOf(runtime.playback.initialized) }
    val playbackTick = remember(runtime) { mutableIntStateOf(0) }
    val translationReplayGeneration = remember(runtime) { mutableIntStateOf(0) }
    val preferencesApplied = remember(runtime) { mutableStateOf(false) }
    val permission = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val synthesis = remember { mutableStateOf(VoiceSynthesisPreferences()) }
    val translationMode = remember { mutableStateOf(false) }
    val translationPair = remember { mutableStateOf(VoiceTranslationPair()) }
    val translationSetupNotice = remember { mutableStateOf<String?>(null) }
    val translationFactories = remember(runtime) { mutableStateOf(VoiceTranslationFactoryCache()) }
    val feedback = remember(context) { VoiceButtonFeedback(context.applicationContext) }
    val microphoneCuePrepared = remember(feedback) { mutableStateOf(false) }
    val modelChangeRunning = remember { mutableStateOf(false) }
    val modelChangeError = remember { mutableStateOf<String?>(null) }
    val tuning = remember { mutableStateOf(VoiceTuningPreferences()) }
    val route = remember { mutableStateOf(runtime.system.routeName()) }
    val routeOpen = remember { mutableStateOf(false) }
    val remaining = remember { mutableIntStateOf(0) }
    return VoiceChatSessionTransientState(
        feedback = feedback,
        profilePreflightReady = profilePreflightReady,
        profilePreflightError = profilePreflightError,
        playbackPrimed = playbackPrimed,
        playbackTick = playbackTick,
        translationReplayGeneration = translationReplayGeneration,
        preferencesApplied = preferencesApplied,
        permission = permission,
        synthesis = synthesis,
        translationMode = translationMode,
        translationPair = translationPair,
        translationSetupNotice = translationSetupNotice,
        translationFactories = translationFactories,
        microphoneCuePrepared = microphoneCuePrepared,
        modelChangeRunning = modelChangeRunning,
        modelChangeError = modelChangeError,
        tuning = tuning,
        route = route,
        routeOpen = routeOpen,
        remaining = remaining
    )
}

@Composable
private fun VoiceChatSessionScreen(
    onEnd: () -> Unit,
    model: ChatViewModel,
    voiceSettings: VoicePreferencesViewModel,
    stores: VoiceModelStores,
    initialChatView: Boolean,
    destinations: VoiceChatDestinations,
    bottomInteractionContent: (@Composable () -> Unit)?,
    runtimeOwner: VoiceRuntimeViewModel = hiltViewModel(),
    backendOwner: VoiceBackendViewModel = hiltViewModel(),
    benchmarkOwner: VoiceSpeechBenchmarkViewModel = hiltViewModel(LocalActivity.current as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current))
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val backend by backendOwner.state.collectAsStateWithLifecycle()
    // A canceled worker can outlive both its settings subtree and its navigation owner.
    val benchmarkOwnership = VoiceSpeechBenchmarkViewModel.audioOwnership
    val benchmarkLease by benchmarkOwnership.collectAsStateWithLifecycle()
    val benchmarkRunning = benchmarkLease != null
    val loaded by model.isLoaded.collectAsStateWithLifecycle()
    val roomReady by model.voiceRoomReady.collectAsStateWithLifecycle()
    val admission by model.voiceAdmission.collectAsStateWithLifecycle()
    val saveError by voiceSettings.saveError.collectAsStateWithLifecycle()
    val features by model.featureSettings.collectAsStateWithLifecycle()
    val messages by model.groupedMessages.collectAsStateWithLifecycle()
    val runs by model.agentRunsById.collectAsStateWithLifecycle()
    val stoppedRuns by model.stoppedVoiceRuns.collectAsStateWithLifecycle()
    val toolEvents by model.toolEventsByRun.collectAsStateWithLifecycle()
    val profiles by model.platformsInApp.collectAsStateWithLifecycle()
    val activeProfiles by model.activePlatformUids.collectAsStateWithLifecycle()
    val room by model.chatRoom.collectAsStateWithLifecycle()
    val chatModels by model.chatPlatformModels.collectAsStateWithLifecycle()
    val savedState = rememberVoiceChatSessionState(initialChatView, stores)
    val listeningEntry = savedState.listeningEntry
    var retainedMessageId by savedState.retainedMessageId
    var retainedRunId by savedState.retainedRunId
    var retainedRevision by savedState.retainedRevision
    fun retainMessage(identity: VoiceMessageIdentity) {
        retainedMessageId = identity.messageId
        retainedRunId = identity.runId
        retainedRevision = identity.revision
    }
    var chatView by savedState.chatView
    val selectedProfile = activeProfiles.singleOrNull()?.let { uid -> profiles.singleOrNull { it.uid == uid && it.enabled } }
        ?.let { resolvePlatformModel(it, chatModels) }
    val approvals by model.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val inputs by model.pendingMcpInput.collectAsStateWithLifecycle()
    val consent by model.pendingFreeToolConsent.collectAsStateWithLifecycle()
    var retainedTranscript by savedState.retainedTranscript
    var retainedTranslationSource by savedState.retainedTranslationSource
    var retainedReply by savedState.retainedReply
    var retainedReview by savedState.retainedReview
    var retainedSubmittedRequestId by savedState.retainedSubmittedRequestId
    var retainedMuted by savedState.retainedMuted
    val speakingNotification by runtimeOwner.background.notification.collectAsStateWithLifecycle()
    val runtime = remember(model, stores, runtimeOwner) {
        runtimeOwner.acquire(model, stores) { session ->
            session.restore(retainedTranscript, retainedReply, retainedReview, pending = false, pendingRequestId = retainedSubmittedRequestId, translationSource = retainedTranslationSource)
            if (retainedMuted) session.mute()
        }
    }
    val transientState = rememberVoiceChatTransientState(context, model, room.id, room.privacyModeLocked, selectedProfile, runtime)
    var profilePreflightReady by transientState.profilePreflightReady
    var profilePreflightError by transientState.profilePreflightError
    val systemAudio = runtime.system
    val localRecognition = runtime.recognition
    val recognitionEvidence by localRecognition.executionEvidence.collectAsStateWithLifecycle()
    val kokoroOutput = runtime.kokoro
    val audio = runtime.audio
    val session = runtime.session
    SideEffect { session.holdAudio(benchmarkOwnership.value != null) }
    val tuningConfiguration = runtime.tuningConfiguration
    val playback = runtime.playback
    val replyProjection = runtime.replyProjection
    var playbackPrimed by transientState.playbackPrimed
    var playbackTick by transientState.playbackTick
    var translationReplayGeneration by transientState.translationReplayGeneration
    var activeMessage by runtime.activeMessage
    var manualMessage by runtime.manualMessage
    var refreshMutedBaseline by runtime.refreshMutedBaseline
    val voiceReady = loaded && roomReady && backend.loaded && playbackPrimed && selectedProfile != null && profilePreflightReady
    val kokoroModel by stores.kokoro.state.collectAsStateWithLifecycle()
    val state by session.presentationStates.collectAsStateWithLifecycle()
    SideEffect {
        if (state.phase != VoicePhase.CLOSED) {
            retainedTranscript = state.transcript
            retainedTranslationSource = state.translationPrompt?.text
            retainedReply = state.reply
            retainedReview = state.hasDraft
            retainedSubmittedRequestId = state.submittedDraftRequestId
            retainedMuted = state.muted
        }
    }
    val enabled = features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)
    val savedPreferences by voiceSettings.preferences.collectAsStateWithLifecycle()
    var preferencesApplied by transientState.preferencesApplied
    val currentEnabled by rememberUpdatedState(enabled && loaded && roomReady && backend.loaded)
    val currentReadyForNewWork by rememberUpdatedState(preferencesApplied)
    var permission by transientState.permission
    var listenAfterPermission by savedState.listenAfterPermission
    var permissionRequestInFlight by savedState.permissionRequestInFlight
    fun cancelListeningIntent() {
        translationReplayGeneration++
        listeningEntry.cancel()
        listenAfterPermission = false
        session.stopAutomaticListening()
    }
    var captions by savedState.captions
    var settingsOpen by savedState.settingsOpen
    var draft by savedState.draft
    var language by savedState.language
    var recognitionBackend by savedState.recognitionBackend
    var recognitionModel by savedState.recognitionModel
    var recognitionCompute by savedState.recognitionCompute
    var recognitionThreads by savedState.recognitionThreads
    val recognitionConfiguration = VoiceRecognitionConfiguration(recognitionModel, recognitionCompute, recognitionThreads).supported()
    val recognitionStore = remember(stores, recognitionConfiguration) { stores.recognition.store(recognitionConfiguration) }
    val localModel by recognitionStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(recognitionStore) { recognitionStore.refresh(forceVerification = false) }
    var synthesis by transientState.synthesis
    fun activeSpeechProfile() = backend.synthesisProfile(synthesis)
    var translationMode by transientState.translationMode
    var translationPair by transientState.translationPair
    var translationSetupNotice by transientState.translationSetupNotice
    var translationFactories by transientState.translationFactories
    var translationOutput by runtime.translationOutput
    val translationModel = backend.streamingModels[MediaPipeSpeechEngine.POCKET_MULTILINGUAL]
    fun outputSelection() = selectVoicePlayback(backend.factory, activeSpeechProfile(), translationOutput)
    fun outputSpeechProfile() = outputSelection().profile
    fun captureLanguage() = if (translationMode) translationPair.sourceLanguage ?: translationPair.baseLanguage else language
    fun captureLanguages() = if (translationMode && translationPair.sourceLanguage == null) listOf(translationPair.baseLanguage, translationPair.translationLanguage) else emptyList()
    fun captureBackend() = if (translationMode) {
        SpeechLanguages.translationRecognitionBackend(translationPair.sourceLanguage, recognitionBackend, recognitionModel)
    } else {
        SpeechLanguages.recognitionBackend(language, recognitionBackend, recognitionModel)
    }
    val translationPreparationFactory = selectTranslationPreparationFactory(
        translationMode,
        translationPair,
        translationFactories,
        translationOutput,
        state.phase,
        activeMessage != null || manualMessage || state.awaitingReply
    )
    val selectedOutputFactory = translationPreparationFactory ?: outputSelection().factory
    VoiceOutputConfigurationEffect(preferencesApplied, kokoroOutput, selectedOutputFactory)
    var kokoroVoice by savedState.kokoroVoice
    var kokoroThreads by savedState.kokoroThreads
    var editBeforeSend by savedState.editBeforeSend
    var spokenReplies by savedState.spokenReplies
    var buttonSounds by savedState.buttonSounds
    val feedback = transientState.feedback
    var microphoneCuePrepared by transientState.microphoneCuePrepared
    LaunchedEffect(feedback, buttonSounds) {
        microphoneCuePrepared = false
        if (buttonSounds) feedback.prepareStart()
        microphoneCuePrepared = true
    }
    DisposableEffect(session, feedback) {
        onDispose {
            session.microphoneFeedback = null
            session.automaticListeningAvailable = { false }
            session.stopAutomaticListening()
            feedback.close()
        }
    }
    SideEffect { session.microphoneFeedback = if (buttonSounds) feedback::start else null }
    var rate by savedState.rate
    var pause by savedState.pause
    var performance by savedState.performance
    var speakWhileGenerating by savedState.speakWhileGenerating
    var kokoroBucket by savedState.kokoroBucket
    var incrementalSpeech by savedState.incrementalSpeech
    var nativeModelVariant by savedState.nativeModelVariant
    var modelChangeRunning by transientState.modelChangeRunning
    var modelChangeError by transientState.modelChangeError
    var tuning by transientState.tuning
    var preferencesInitialized by savedState.preferencesInitialized
    LaunchedEffect(model, enabled, roomReady, room.id, room.privacyModeLocked, selectedProfile, lifecycleState, settingsOpen) {
        profilePreflightReady = false
        profilePreflightError = null
        val profile = selectedProfile ?: return@LaunchedEffect
        if (!enabled || !roomReady || settingsOpen || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        try {
            // This checks the same retained account/policy used at dispatch. A
            // changed selection cancels the old check before it can open capture.
            model.preflightVoiceProfile(profile)
            currentCoroutineContext().ensureActive()
            profilePreflightReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            profilePreflightError = failure.message?.removePrefix("POLICY_RESTRICTED:")?.trim()
                ?: "This AI profile needs attention. Choose another profile or review its settings in chat."
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            profilePreflightError = "Could not check this AI profile. Open chat to review its connection, or choose another profile."
        }
    }
    LaunchedEffect(savedPreferences, runtime) {
        val saved = savedPreferences ?: return@LaunchedEffect
        if (!preferencesApplied) {
            tuning = saved.tuning
            synthesis = saved.synthesis
            translationMode = saved.translationMode
            translationPair = saved.translationPair
            translationSetupNotice = saved.translationSetupNotice
            if (!preferencesInitialized) {
                language = saved.language
                recognitionBackend = saved.recognitionBackend
                recognitionModel = saved.recognitionModel
                recognitionCompute = saved.recognitionCompute
                recognitionThreads = saved.recognitionThreads
                captions = saved.captions
                editBeforeSend = saved.review
                spokenReplies = saved.spokenReplies
                buttonSounds = saved.buttonSounds
                rate = saved.rate
                pause = saved.pause
                performance = saved.performance
                speakWhileGenerating = saved.speakWhileGenerating
                kokoroBucket = saved.kokoroBucket
                incrementalSpeech = saved.incrementalSpeech
                nativeModelVariant = saved.nativeModelVariant
                kokoroVoice = synthesis.nativeVoice(saved.kokoroVoice)
                kokoroThreads = saved.kokoroThreads
                preferencesInitialized = true
            }
            audio.recognitionBackend = captureBackend()
            audio.requireLocalOutput = outputSelection().requireLocalOutput
            localRecognition.configure(VoiceRecognitionConfiguration(recognitionModel, recognitionCompute, recognitionThreads).supported())
            localRecognition.languageTag = captureLanguage()
            systemAudio.translationLanguages = captureLanguages()
            tuningConfiguration.apply(tuning, outputSpeechProfile().dictionaryLanguage, java.util.Locale.forLanguageTag(captureLanguage()).language)
            audio.kokoroVoice = kokoroVoice
            kokoroOutput?.configureThreads(kokoroThreads)
            kokoroOutput?.configurePacing(pause, performance)
            kokoroOutput?.configureCaptionAlignment(captions)
            session.performanceMode = performance
            session.speakWhileGenerating = speakWhileGenerating
            if (session.states.value.speaker != spokenReplies) session.speaker()
            // Configure before permitting the first listen, including after restoring the screen.
            systemAudio.synthesisLanguageTag = outputSpeechProfile().tag
            model.voiceReplyPolicy = VoiceResponsePolicy.forLanguage(activeSpeechProfile().tag, translationMode)
            systemAudio.languageTag = captureLanguage()
            systemAudio.allowOnlineSpeech = false
            systemAudio.speechRate = rate
            session.editBeforeSend = editBeforeSend
            session.translationOnly = translationMode
            preferencesApplied = true
        }
    }
    val recognitionReady = if (captureBackend() == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
        localModel.status == LocalVoiceModelStatus.READY && recognitionModel.supportsLanguage(captureLanguage())
    } else {
        remember(context, lifecycleState) { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    }
    fun applyAudioConfiguration() {
        val onlineAllowed = false
        if (
            audio.recognitionBackend != captureBackend() ||
            localRecognition.configuration != recognitionConfiguration ||
            localRecognition.languageTag != captureLanguage() ||
            systemAudio.languageTag != captureLanguage() ||
            systemAudio.translationLanguages != captureLanguages() ||
            systemAudio.allowOnlineSpeech != onlineAllowed
        ) {
            session.recognitionConfigurationChanged()
        }
        audio.synthesisSupported = translationOutput != null || translationMode || backend.backend.forSynthesis(synthesis) == backend.backend
        audio.requireLocalOutput = outputSelection().requireLocalOutput
        audio.recognitionBackend = captureBackend()
        localRecognition.configure(recognitionConfiguration)
        localRecognition.languageTag = captureLanguage()
        systemAudio.translationLanguages = captureLanguages()
        tuningConfiguration.apply(tuning, outputSpeechProfile().dictionaryLanguage, java.util.Locale.forLanguageTag(captureLanguage()).language)
        systemAudio.synthesisLanguageTag = outputSpeechProfile().tag
        model.voiceReplyPolicy = VoiceResponsePolicy.forLanguage(activeSpeechProfile().tag, translationMode)
        systemAudio.languageTag = captureLanguage()
        systemAudio.allowOnlineSpeech = onlineAllowed
        systemAudio.speechRate = rate
        audio.kokoroVoice = kokoroVoice
        kokoroOutput?.configureThreads(kokoroThreads)
        kokoroOutput?.configurePacing(pause, performance)
        kokoroOutput?.configureCaptionAlignment(captions)
        session.performanceMode = performance
        session.speakWhileGenerating = speakWhileGenerating
        session.editBeforeSend = editBeforeSend
        session.translationOnly = translationMode
    }
    fun translationFactorySettings() = VoiceTranslationFactorySettings(rate, kokoroBucket, incrementalSpeech)
    fun changeSpeechSettings(nextRate: Float = rate, nextBucket: MediaPipeKokoroBucket = kokoroBucket, nextIncremental: Boolean = incrementalSpeech) {
        audio.stop()
        val next = VoiceTranslationFactorySettings(nextRate, nextBucket, nextIncremental)
        val previous = translationFactorySettings()
        translationOutput = translationPlaybackAfterSettingsChange(translationOutput, previous, next)
        translationFactories = translationFactories.afterSettingsChange(previous, next)
        rate = next.rate
        kokoroBucket = next.bucket
        incrementalSpeech = next.incrementalInput
        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant, synthesis)
    }
    fun translationVoiceKey(target: VoiceTranslationTarget) = "${target.targetLanguage}:${target.voiceId}"
    fun applyTranslationOutput(target: VoiceTranslationTarget, factory: VoicePhraseEngineFactory) {
        translationOutput = VoiceTranslationPlayback(target, factory)
        // Configure the destination before any translated phrase can open an audio stream.
        kokoroOutput?.configureEngine(factory)
        audio.synthesisSupported = true
        // A different system voice cannot fulfill this turn's selected language/voice pair.
        audio.requireLocalOutput = true
        systemAudio.synthesisLanguageTag = target.targetLanguage
        MediaPipeSpeechVoices.forVoice(target.voiceId)?.let { tuningConfiguration.apply(tuning, it.dictionaryLanguage, java.util.Locale.forLanguageTag(target.sourceLanguage).language) }
    }
    fun acceptedVoicePolicy(identity: VoiceMessageIdentity): VoiceResponsePolicy? {
        if (identity.runId.isBlank()) return VoiceResponsePolicy.CONVERSATIONAL
        val run = model.agentRunsById.value[identity.runId] ?: return null
        if (run.chatId != room.id || run.assistantMessageId != identity.messageId) return null
        val source = model.groupedMessages.value.userMessages.firstOrNull { it.id == run.userMessageId && it.chatId == run.chatId } ?: return null
        return source.timeline.lastOrNull { it.voiceResponsePolicy != null }?.voiceResponsePolicy ?: VoiceResponsePolicy.CONVERSATIONAL
    }
    val translationPlaybackNeeded = translationMode ||
        translationOutput != null ||
        messages.userMessages.any { message ->
            message.timeline.any { it.voiceResponsePolicy?.translationOnly == true }
        }
    LaunchedEffect(preferencesApplied, translationPlaybackNeeded) {
        if (preferencesApplied && translationPlaybackNeeded) backendOwner.refreshTranslationModel()
    }
    LaunchedEffect(preferencesApplied, translationPlaybackNeeded, translationPair, translationModel?.status, translationFactorySettings(), translationFactories.generation) {
        translationFactories = translationFactories.cleared()
        val pair = translationPair.validated() ?: return@LaunchedEffect
        if (!preferencesApplied || !translationPlaybackNeeded || translationModel?.status != LocalVoiceModelStatus.READY) return@LaunchedEffect
        val request = translationFactories.preparation(translationFactorySettings(), pair)
        try {
            val targets = listOf(
                VoiceTranslationTarget(pair.translationLanguage, pair.baseLanguage, pair.baseVoiceId),
                VoiceTranslationTarget(pair.baseLanguage, pair.translationLanguage, pair.translationVoiceId)
            )
            val prepared = targets.associate { target ->
                translationVoiceKey(target) to backendOwner.translationFactory(target.targetLanguage, target.voiceId)
            }
            currentCoroutineContext().ensureActive()
            translationFactories = translationFactories.publish(request, translationFactorySettings(), translationPair.validated(), prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Submission retains the utterance and shows the actionable model-install error.
        }
    }
    SideEffect {
        session.prepareDraft = if (translationMode && preferencesApplied) {
            { text, complete ->
                val pair = translationPair.validated()
                if (pair == null) {
                    complete(VoiceDraftPreparation(rejection = "Choose two different languages in Translation languages, then send your saved words."))
                } else {
                    settingsScope.launch {
                        try {
                            val detected = if (pair.sourceLanguage == null) {
                                runtimeOwner.detectTranslationLanguage(text, listOf(pair.baseLanguage, pair.translationLanguage))
                            } else {
                                null
                            }
                            val target = pair.resolve(pair.sourceLanguage ?: detected?.languageTag?.takeIf { detected.status == VoiceTranslationLanguageStatus.DETECTED })
                            if (target == null) {
                                complete(VoiceDraftPreparation(rejection = "The spoken language is unclear. Choose the spoken language in Translation languages, then send your saved words."))
                            } else {
                                val factory = translationFactories[translationVoiceKey(target)] ?: backendOwner.translationFactory(target.targetLanguage, target.voiceId)
                                currentCoroutineContext().ensureActive()
                                complete(
                                    VoiceDraftPreparation(commit = {
                                        applyTranslationOutput(target, factory)
                                        model.voiceReplyPolicy = target.responsePolicy
                                    })
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            complete(VoiceDraftPreparation(rejection = "Install the Pocket Multilingual voice pack in Translation languages, then retry the saved text."))
                        }
                    }
                }
            }
        } else {
            null
        }
    }
    DisposableEffect(session) { onDispose { session.prepareDraft = null } }
    SideEffect {
        if (preferencesApplied) applyAudioConfiguration()
    }
    LaunchedEffect(preferencesApplied, kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant, synthesis) {
        if (preferencesApplied) backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant, synthesis)
    }
    var route by transientState.route
    var routeOpen by transientState.routeOpen
    var remaining by transientState.remaining
    DisposableEffect(audio, session) {
        systemAudio.routeChanged = {
            session.pause("audio_route_changed")
            route = systemAudio.routeName()
        }
        onDispose { systemAudio.routeChanged = null }
    }
    LaunchedEffect(state.retryAtMillis, lifecycleState) {
        while (state.retryAtMillis > 0 && lifecycleState == Lifecycle.State.RESUMED) {
            remaining = ((state.retryAtMillis - System.nanoTime() / 1_000_000 + 999) / 1000).coerceAtLeast(0).toInt()
            session.tick()
            delay(250)
        }
        remaining = 0
    }
    LaunchedEffect(state.transcript) { draft = state.transcript }
    val voiceScreenActive = enabled && lifecycleState == Lifecycle.State.RESUMED && !settingsOpen && !routeOpen && !benchmarkRunning
    val foregroundVoice = voiceScreenActive && roomReady
    val automaticListeningReady = foregroundVoice && voiceReady && preferencesApplied && recognitionReady && microphoneCuePrepared && approvals.isEmpty() && inputs.isEmpty() && consent == null
    SideEffect {
        session.automaticListeningAvailable = { automaticListeningReady && !runtimeOwner.background.playingElsewhere(runtime) }
        if (chatView || settingsOpen || routeOpen) listeningEntry.cancel()
    }
    val installOnVoiceLaunch = foregroundVoice && loaded && room.id > 0 && !chatView
    LaunchedEffect(voiceSettings, installOnVoiceLaunch) {
        if (installOnVoiceLaunch) voiceSettings.onVoiceLaunch()
    }
    val prepareProfile = foregroundVoice && room.id > 0 && !chatView && profilePreflightReady && features.readiness.enabled && !state.awaitingReply
    val networkEpoch by ReadinessHints.networkEpoch.collectAsStateWithLifecycle()
    val readinessEpoch by dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.changes.collectAsStateWithLifecycle()
    val chatTools by model.chatToolConfig.collectAsStateWithLifecycle()
    val prepareListening = foregroundVoice && profilePreflightReady && features.readiness.enabled && !chatView && !translationMode && !room.privacyModeLocked && !room.isTemporary
    LaunchedEffect(prepareListening, session, selectedProfile, room.id, chatTools, features.readiness, features.remoteMcpConnections, networkEpoch, readinessEpoch) {
        val profile = selectedProfile ?: return@LaunchedEffect
        if (prepareListening) {
            model.prepareListeningSession(session.states, profile, chatTools, features.remoteMcpConnections && runtimeOwner.canPrepareRemoteCatalogues(features.readiness))
        }
    }
    LaunchedEffect(prepareProfile, selectedProfile, features.readiness, room.id, room.privacyModeLocked, networkEpoch) {
        val profile = selectedProfile ?: return@LaunchedEffect
        if (prepareProfile) runtimeOwner.prepareProfile(profile, features.readiness, room.id, room.privacyModeLocked)
    }
    LaunchedEffect(session, state.reply, state.awaitingReply, performance, speakWhileGenerating, foregroundVoice) {
        if (!foregroundVoice) return@LaunchedEffect
        session.awaitSpeechFlush()
    }
    val preparingTranslation = translationOutput != null || translationPreparationFactory != null
    val localSpeech = preparingTranslation || backend.backend != VoiceSpeechBackend.GATEWAY_CUDA
    val prepareSpeech = voiceScreenActive &&
        preferencesApplied &&
        spokenReplies &&
        backend.loaded &&
        (if (translationOutput != null || translationMode) preparingTranslation && translationModel?.status == LocalVoiceModelStatus.READY else backend.speechModelReady(kokoroModel)) &&
        (preparingTranslation || backend.backend.forSynthesis(synthesis) == backend.backend) &&
        (localSpeech || voiceReady) &&
        (!chatView || state.awaitingReply)
    val preparation = if (prepareSpeech) {
        VoicePreparationPolicy.stage(
            state.phase,
            state.awaitingReply,
            state.hasDraft,
            localSpeech,
            admission?.stage == VoiceAdmissionStage.ACCEPTED,
            openingMicrophone = captureBackend() == VoiceRecognitionBackend.LOCAL_MOONSHINE && !state.muted && (recognitionReady || localModel.status == LocalVoiceModelStatus.CHECKING),
            turnReady = roomReady && selectedProfile != null
        )
    } else {
        VoicePreparation.NONE
    }
    // STARTING may cancel an earlier output as the microphone takes ownership. Re-arm the
    // asset stage there, then queue native preparation once capture owns the exclusive gate.
    LaunchedEffect(kokoroOutput, preparation, state.phase, kokoroVoice, rate, kokoroThreads, performance, selectedOutputFactory, enabled, loaded, roomReady, synthesis) {
        val voice = kokoroVoice ?: KokoroVoice.HEART.takeIf { preparingTranslation }
        if (preparation != VoicePreparation.NONE && voice != null && benchmarkOwnership.value == null) {
            kokoroOutput?.prewarm(voice, assetsOnly = preparation == VoicePreparation.ASSETS)
        } else {
            kokoroOutput?.cancelPrewarm()
            if (!enabled || !roomReady || translationOutput == null && !translationMode && backend.backend.forSynthesis(synthesis) != backend.backend) kokoroOutput?.releaseWarmModel()
        }
    }
    LaunchedEffect(localRecognition, recognitionConfiguration, captureLanguage(), recognitionReady, preferencesApplied, voiceScreenActive, state.phase, captureBackend()) {
        if (voiceScreenActive &&
            preferencesApplied &&
            recognitionReady &&
            captureBackend() == VoiceRecognitionBackend.LOCAL_MOONSHINE &&
            benchmarkOwnership.value == null &&
            state.phase in setOf(VoicePhase.PAUSED, VoicePhase.REVIEWING, VoicePhase.THINKING)
        ) {
            localRecognition.prewarm()
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequestInFlight = false
        permission = granted
        session.access(currentEnabled, granted, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), readyForNewWork = currentReadyForNewWork)
        if (!granted) {
            listenAfterPermission = false
            session.permissionDenied()
        }
    }
    fun listen() {
        listeningEntry.cancel()
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null || !enabled || !voiceReady || !preferencesApplied || !microphoneCuePrepared || !recognitionReady && !state.hasDraft) return
        if (runtimeOwner.background.playingElsewhere(runtime)) {
            runtimeOwner.background.stopCurrent()
        }
        applyAudioConfiguration()
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        session.access(currentEnabled, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), readyForNewWork = currentReadyForNewWork)
        if (permission) {
            session.listenForConversation()
        } else {
            listenAfterPermission = true
            permissionRequestInFlight = true
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun savePreferences() {
        voiceSettings.save(VoicePreferences(language = language, recognitionBackend = recognitionBackend, recognitionModel = recognitionModel, recognitionCompute = recognitionCompute, recognitionThreads = recognitionThreads, synthesis = synthesis, translationMode = translationMode, translationPair = translationPair, captions = captions, review = editBeforeSend, rate = rate, kokoroVoice = kokoroVoice, spokenReplies = spokenReplies, kokoroThreads = kokoroThreads, buttonSounds = buttonSounds, pause = pause, performance = performance, speakWhileGenerating = speakWhileGenerating, kokoroBucket = kokoroBucket, incrementalSpeech = incrementalSpeech, nativeModelVariant = nativeModelVariant, tuning = tuning))
    }
    fun changeSynthesis(selected: VoiceSynthesisPreferences) {
        if (synthesis == selected) return
        audio.stop()
        synthesis = selected
        kokoroVoice = selected.nativeVoice(kokoroVoice)
        applyAudioConfiguration()
        backendOwner.configureSpeech(kokoroVoice, rate, synthesis = selected)
        savePreferences()
    }
    fun previewSpeech(selected: KokoroVoice, report: (String?) -> Unit) {
        audio.stop()
        backendOwner.configureSpeech(selected, rate, synthesis = synthesis)
        val selectedBackend = backendOwner.state.value
        if (!selectedBackend.loaded) {
            report("Speech settings are updating. Try the preview again shortly.")
        } else if (kokoroOutput == null) {
            report("Speech playback is unavailable on this device.")
        } else {
            report(null)
            kokoroOutput.configureEngine(selectedBackend.factory)
            playKokoroPreview(kokoroOutput, selected, ::applyAudioConfiguration, activeSpeechProfile().preview) { message, _ -> report(message) }
        }
    }
    fun setSpokenReplies(value: Boolean) {
        spokenReplies = value
        if (session.states.value.speaker != value) session.speaker()
        savePreferences()
    }
    fun end() {
        if (preferencesApplied) savePreferences()
        session.close()
        model.cancelActiveRuns()
        onEnd()
    }
    fun leaveTranscript() {
        if (preferencesApplied) savePreferences()
        if (room.isTemporary || !runtimeOwner.background.owns(runtime)) session.close()
        model.leaveConversation(onEnd)
    }
    fun pauseCapture() {
        cancelListeningIntent()
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)) session.pause("transcript_opened")
    }
    fun openChat() {
        if (preferencesApplied) savePreferences()
        pauseCapture()
        chatView = true
    }
    fun closeSettings() {
        benchmarkOwner.cancel()
        audio.stop()
        savePreferences()
        settingsOpen = false
    }
    BackHandler {
        if (settingsOpen) {
            closeSettings()
        } else if (chatView) {
            leaveTranscript()
        } else if (runtimeOwner.background.owns(runtime)) {
            leaveTranscript()
        } else {
            end()
        }
    }
    DisposableEffect(lifecycle, session, benchmarkOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    runtimeOwner.resume()
                    permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    session.holdAudio(benchmarkOwnership.value != null)
                    session.access(currentEnabled, permission, true, readyForNewWork = currentReadyForNewWork)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    session.stopAutomaticListening()
                    if (!permissionRequestInFlight) cancelListeningIntent()
                    if (event == Lifecycle.Event.ON_STOP) benchmarkOwner.cancel()
                    refreshMutedBaseline = true
                    kokoroOutput?.cancelPrewarm()
                    session.access(currentEnabled, permission, false, readyForNewWork = currentReadyForNewWork)
                    systemAudio.releaseRecognition(preservePlayback = session.backgroundPlayback)
                    runtimeOwner.pause(runtime)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            benchmarkOwner.cancel()
            refreshMutedBaseline = true
            kokoroOutput?.cancelPrewarm()
            session.access(currentEnabled, permission, false, readyForNewWork = currentReadyForNewWork)
            systemAudio.releaseRecognition(preservePlayback = session.backgroundPlayback)
            runtimeOwner.pause(runtime)
        }
    }
    LaunchedEffect(currentEnabled, currentReadyForNewWork, voiceReady, lifecycleState, permission, listenAfterPermission, recognitionReady, microphoneCuePrepared, settingsOpen, routeOpen, benchmarkRunning) {
        session.holdAudio(benchmarkOwnership.value != null)
        session.access(currentEnabled, permission, lifecycleState == Lifecycle.State.RESUMED, readyForNewWork = currentReadyForNewWork)
        if (listenAfterPermission && permission && lifecycleState == Lifecycle.State.RESUMED && voiceReady && preferencesApplied && microphoneCuePrepared) {
            listenAfterPermission = false
            if (!settingsOpen && !routeOpen && recognitionReady && session.state.microphoneAction == VoiceMicrophoneAction.LISTEN) listen()
        }
    }
    LaunchedEffect(session, room.id, loaded, roomReady, initialChatView, automaticListeningReady, chatView, messages, state.phase, speakingNotification?.token) {
        if (!loaded || !roomReady || room.id <= 0) return@LaunchedEffect
        // Read the loaded room's live history, not a possibly older Compose collector snapshot.
        val currentMessages = model.groupedMessages.value
        val newConversation = !initialChatView && currentMessages.userMessages.isEmpty() && currentMessages.assistantMessages.all { it.isEmpty() }
        if (listeningEntry.take(automaticListeningReady && !chatView && !runtimeOwner.background.playingElsewhere(runtime), newConversation, session.states.value)) listen()
    }
    LaunchedEffect(admission) {
        val accepted = admission ?: return@LaunchedEffect
        session.admission(accepted.stage, accepted.reason, accepted.requestId)
    }
    val canOfferOpening = foregroundVoice &&
        voiceReady &&
        preferencesApplied &&
        spokenReplies &&
        speakWhileGenerating &&
        !translationMode &&
        tuning.spokenAcknowledgments &&
        state.speaker &&
        !manualMessage &&
        !room.privacyModeLocked &&
        approvals.isEmpty() &&
        inputs.isEmpty() &&
        consent == null &&
        !runtimeOwner.background.playingElsewhere(runtime) &&
        (state.phase == VoicePhase.THINKING && state.awaitingReply || chatView && state.phase == VoicePhase.PAUSED)
    val openingPlan = remember(canOfferOpening, room.id, selectedProfile?.uid, admission, state.transcript, chatView, messages, runs, toolEvents, activeSpeechProfile().tag) {
        if (canOfferOpening) voiceOpeningPlan(admission, state.transcript, chatView, room.id, selectedProfile.uid, messages, runs, toolEvents, activeSpeechProfile().tag) else null
    }
    val dueOpening = rememberDueVoiceOpening(openingPlan)
    LaunchedEffect(loaded, roomReady, messages, runs, stoppedRuns, admission, dueOpening, speakingNotification?.token, enabled, backend.loaded, spokenReplies, speakWhileGenerating, preferencesApplied, lifecycleState, state.phase, state.awaitingReply, settingsOpen, routeOpen, refreshMutedBaseline, playbackTick, benchmarkRunning, approvals.isEmpty(), inputs.isEmpty(), consent, chatView, translationFactories, translationMode, translationPair) {
        if (!loaded || !roomReady || benchmarkOwnership.value != null) return@LaunchedEffect
        // Restoration publishes messages before loaded=true. Read the current flow snapshot so
        // separate Compose collectors cannot accidentally prime from an empty history.
        val messageSnapshot = model.groupedMessages.value
        val runSnapshot = model.agentRunsById.value
        val replies = replyProjection.update(messageSnapshot.assistantMessages, runSnapshot) { runSnapshot[it]?.status }
        val foregroundOutput = enabled && preferencesApplied && backend.loaded && lifecycleState == Lifecycle.State.RESUMED && !settingsOpen && !routeOpen
        playback.stopRuns(model.stoppedVoiceRuns.value)
        playback.observe(replies.filterNot { it.identity.runId in model.stoppedVoiceRuns.value }, autoplay = spokenReplies && foregroundOutput && !refreshMutedBaseline && session.automaticAudioAvailable())
        playbackPrimed = playback.initialized
        if (!foregroundOutput) return@LaunchedEffect
        refreshMutedBaseline = false
        playback.retireSuperseded()?.let { identity ->
            session.supersedeMessage(identity.key)
            activeMessage = null
            manualMessage = false
        }
        val currentSession = session.states.value
        if (manualMessage) {
            if (currentSession.awaitingReply || currentSession.phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) return@LaunchedEffect
            manualMessage = false
            activeMessage = null
            playback.finish()
        }
        if (playback.active != null && !currentSession.awaitingReply && currentSession.phase !in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            playback.finish()
            activeMessage = null
        }
        if (playback.active == null) {
            // Recheck live ownership after the grace period. A filled reply always wins admission.
            val opening = dueOpening?.takeIf {
                voiceOpeningPlan(model.voiceAdmission.value, currentSession.transcript, chatView, room.id, selectedProfile?.uid, messageSnapshot, runSnapshot, model.toolEventsByRun.value, activeSpeechProfile().tag) == it
            }
            val candidate = playback.peek(opening?.identity) ?: return@LaunchedEffect
            if (candidate.readAloud) {
                // Message and run flows can publish separately. Missing source metadata is
                // not evidence that this is ordinary speech in the previous output language.
                when (val selection = selectTranslationPlayback(acceptedVoicePolicy(candidate.identity), translationPair, translationOutput?.target)) {
                    VoiceTranslationPlaybackSelection.MissingPolicy, VoiceTranslationPlaybackSelection.IncompatiblePair -> return@LaunchedEffect
                    is VoiceTranslationPlaybackSelection.Translated -> {
                        val target = selection.target
                        val acceptedOutput = translationOutput?.takeIf { it.target == target }
                        val factory = acceptedOutput?.factory ?: translationFactories[translationVoiceKey(target)]
                        // Preparation cannot consume this generation or open the previous language's voice.
                        if (factory == null) return@LaunchedEffect
                        applyTranslationOutput(target, factory)
                    }
                    VoiceTranslationPlaybackSelection.Ordinary -> {
                        translationOutput = null
                        applyAudioConfiguration()
                        kokoroOutput?.configureEngine(backend.factory)
                    }
                }
            }
            val next = playback.next(opening?.identity) ?: return@LaunchedEffect
            val source = if (translationMode) {
                val run = runSnapshot[next.identity.runId]
                messageSnapshot.userMessages.firstOrNull { user ->
                    run != null &&
                        run.chatId == room.id &&
                        run.assistantMessageId == next.identity.messageId &&
                        user.chatId == run.chatId &&
                        user.id == run.userMessageId &&
                        user.timeline.any { it.voiceResponsePolicy?.translationOnly == true }
                }?.content
            } else {
                null
            }
            if (!session.beginMessage(next.identity.key, diagnosticRunId = next.identity.runId, readAloud = next.readAloud, translationSource = source)) {
                playback.finish()
                return@LaunchedEffect
            }
            activeMessage = next.identity.takeIf { next.readAloud }
            retainMessage(next.identity)
            if (opening != null && next.identity == opening.identity && next.text.isBlank() && !next.complete && next.readAloud) {
                session.offerOpening(next.identity.key, opening.cue)
            }
        }
        val pending = playback.active
        if (pending?.complete == true) {
            replyProjection.pendingTerminal(pending.identity.messageId)?.let { observation ->
                val observedMessage = observation.source
                val speakerAtRead = session.states.value.speaker
                val reportReadFailure = session.terminalReadFailureReporter(pending.identity.key)
                val terminal = try {
                    model.terminalVoiceReply(pending.identity.messageId, pending.identity.runId, observedMessage.ownershipFence)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (playback.active != pending || manualMessage) return@LaunchedEffect
                    reportReadFailure(error)
                    return@LaunchedEffect
                } ?: return@LaunchedEffect
                currentCoroutineContext().ensureActive()
                val currentMessage = model.groupedMessages.value.assistantMessages.asSequence().flatten().firstOrNull { it.id == pending.identity.messageId }
                if (!enabled ||
                    lifecycle.currentState != Lifecycle.State.RESUMED ||
                    settingsOpen ||
                    routeOpen ||
                    benchmarkOwnership.value != null ||
                    manualMessage ||
                    playback.active != pending ||
                    session.states.value.speaker != speakerAtRead ||
                    currentMessage == null ||
                    currentMessage.currentRunId != pending.identity.runId ||
                    currentMessage.ownershipFence != observedMessage.ownershipFence
                ) {
                    return@LaunchedEffect
                }
                // Separate Room collectors may still expose an old blank/prefix row with a
                // new terminal status. Only the matching durable publication is delivered.
                playback.terminalUpdate(
                    pending,
                    dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(terminal.message.content),
                    failed = terminal.run.status != AgentRunStatus.COMPLETED
                )?.let { next ->
                    val failure = terminal.message.timeline.lastOrNull { it.profileFailure != null }?.profileFailure
                        ?: terminal.run.terminalError?.let { dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(it) }
                    session.reply(next.identity.key, next.text, next.failed, failure, terminalStatus = terminal.run.status)
                }
                observation.terminalPublished = true
            }
        } else {
            playback.update()?.let { next -> session.streamReply(next.identity.key, next.text) }
        }
        val deliveredState = session.states.value
        if (!deliveredState.awaitingReply && deliveredState.phase !in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            playback.finish()
            activeMessage = null
            // Silent completions may begin and end PAUSED in one main-thread turn. Advance
            // queued responses explicitly instead of relying on a transient Compose phase.
            if (playback.hasPending) playbackTick++
        }
    }
    val waitingApproval = approvals.isNotEmpty() || inputs.isNotEmpty() || consent != null
    val profileName = profiles.firstOrNull { it.uid in activeProfiles }?.let { it.name.ifBlank { it.model } } ?: "Your AI profile"
    val status = when {
        !enabled -> "Voice chat is turned off"
        state.phase == VoicePhase.PREPARING -> "Preparing voice"
        state.phase == VoicePhase.SPEAKING -> "Speaking"
        profilePreflightError != null -> "AI profile needs attention"
        !voiceReady || !preferencesApplied -> "Getting ready"
        waitingApproval -> "Your attention is needed"
        state.phase == VoicePhase.REVIEWING -> "Review your message"
        !recognitionReady -> if (translationMode) "Set up speech languages" else "Set up local speech"
        remaining > 0 -> "Retry in $remaining seconds"
        state.recoveryAction != null -> "Speech setup needs attention"
        state.phase == VoicePhase.STARTING -> "Starting microphone"
        state.phase == VoicePhase.FINALIZING -> if (translationMode && state.hasDraft) "Preparing translation" else "Finishing your words"
        state.phase == VoicePhase.THINKING && admission?.stage == VoiceAdmissionStage.CHECKING -> "Checking your AI profile"
        state.phase == VoicePhase.THINKING && admission?.stage == VoiceAdmissionStage.QUEUED -> "Message queued"
        state.phase == VoicePhase.THINKING -> "Thinking"
        state.muted -> "Microphone muted"
        state.phase == VoicePhase.LISTENING -> "Listening"
        state.phase == VoicePhase.ERROR -> "Let’s try again"
        else -> "Ready when you are"
    }
    fun stopMessage() {
        translationReplayGeneration++
        session.stopSpeaking()
        activeMessage = null
    }
    fun toggleMicrophone() {
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null) return
        when (session.state.microphoneAction) {
            VoiceMicrophoneAction.LISTEN -> listen()
            VoiceMicrophoneAction.FINISH_CAPTURE -> session.finishListening()
            VoiceMicrophoneAction.STOP -> {
                translationReplayGeneration++
                cancelListeningIntent()
                val captureOnly = session.state.phase == VoicePhase.FINALIZING
                val playingRun = playback.active?.identity?.runId
                if (!manualMessage && !captureOnly) model.stopVoiceTurn(playingRun)
                playback.finish()
                activeMessage = null
                manualMessage = false
                session.stopTurn()
                if (buttonSounds) feedback.stop()
            }
        }
    }
    fun readResponse(identity: VoiceMessageIdentity, text: String, includeDetails: Boolean = false) {
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null || text.isBlank() || !preferencesApplied) return
        pauseCapture()
        session.access(currentEnabled, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), readyForNewWork = currentReadyForNewWork)
        fun begin() {
            if (session.beginMessage(identity.key, manual = true, diagnosticRunId = identity.runId.ifBlank { identity.key }, includeDetails = includeDetails)) {
                playback.manual(identity)
                manualMessage = true
                activeMessage = identity
                retainMessage(identity)
                session.reply(identity.key, text)
            }
        }
        val selection = selectTranslationPlayback(acceptedVoicePolicy(identity), translationPair, translationOutput?.target)
        if (selection == VoiceTranslationPlaybackSelection.MissingPolicy) {
            session.fail("The saved reply's language is still unavailable. Wait for the conversation to finish loading before replaying.", reportFailure = false)
            return
        }
        if (selection == VoiceTranslationPlaybackSelection.IncompatiblePair) {
            session.fail("This saved reply uses a language outside the selected translation pair. Select its language in Translation languages before replaying.", reportFailure = false)
            return
        }
        if (selection is VoiceTranslationPlaybackSelection.Translated) {
            val target = selection.target
            session.pause("translation_replay_preparing")
            val owner = session.playbackPreparationOwner() ?: return
            val ticket = ++translationReplayGeneration
            settingsScope.launch {
                try {
                    val factory = translationOutput?.takeIf { it.target == target }?.factory
                        ?: translationFactories[translationVoiceKey(target)]
                        ?: backendOwner.translationFactory(target.targetLanguage, target.voiceId)
                    currentCoroutineContext().ensureActive()
                    if (ticket != translationReplayGeneration || !session.ownsPlaybackPreparation(owner) || lifecycle.currentState != Lifecycle.State.RESUMED || settingsOpen || routeOpen || benchmarkOwnership.value != null) return@launch
                    applyTranslationOutput(target, factory)
                    begin()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (ticket == translationReplayGeneration && session.ownsPlaybackPreparation(owner)) {
                        session.fail("Install the Pocket Multilingual voice pack in Translation languages to replay this translation.", reportFailure = false)
                    }
                }
            }
        } else {
            translationReplayGeneration++
            translationOutput = null
            applyAudioConfiguration()
            kokoroOutput?.configureEngine(backend.factory)
            begin()
        }
    }
    val latestRead = rememberUpdatedState<(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2, String) -> Unit> { message, text ->
        readResponse(message.voiceIdentity(), text)
    }
    val latestReadDetails = rememberUpdatedState<(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2, String) -> Unit> { message, text ->
        readResponse(message.voiceIdentity(), text, includeDetails = true)
    }
    // On a fresh navigation entry the service can still own the original runtime for this room.
    val serviceReply = speakingNotification?.takeIf { it.roomId == room.id && it.message != null && !runtimeOwner.background.owns(runtime) }
    val latestStop = rememberUpdatedState(::stopMessage)
    val latestPauseCapture = rememberUpdatedState(::pauseCapture)
    val outputAvailable = !benchmarkRunning &&
        enabled &&
        preferencesApplied &&
        loaded &&
        roomReady &&
        (backend.speechModelReady(kokoroModel) || translationPlaybackNeeded && translationModel?.status == LocalVoiceModelStatus.READY)
    // Meter ticks do not replace controls or invalidate every transcript message.
    val messageControls = remember(activeMessage, state.phase, outputAvailable, serviceReply) {
        VoiceMessageControls(
            active = serviceReply?.message ?: activeMessage,
            phase = serviceReply?.phase ?: state.phase,
            available = outputAvailable,
            read = { message, text -> latestRead.value(message, text) },
            stop = { if (serviceReply != null) runtimeOwner.background.stop(serviceReply.token) else latestStop.value() },
            pauseCapture = { latestPauseCapture.value() },
            readDetails = { message, text -> latestReadDetails.value(message, text) }
        )
    }
    VoiceHealthObservation(session.performanceHistory, sampleMemory = settingsOpen)
    VoiceChatSessionPresentation(
        settingsOpen = settingsOpen,
        chatView = chatView,
        routeOpen = routeOpen,
        settings = {
            VoiceSettingsScreen(
                state = VoiceSettingsUiState(
                    profiles = profiles,
                    activeProfiles = activeProfiles,
                    session = state,
                    saveError = saveError,
                    localModel = localModel,
                    language = language,
                    recognitionBackend = recognitionBackend,
                    recognitionConfiguration = recognitionConfiguration,
                    recognitionEvidence = recognitionEvidence,
                    synthesis = synthesis,
                    kokoroVoice = kokoroVoice,
                    editBeforeSend = editBeforeSend,
                    captions = captions,
                    spokenReplies = spokenReplies,
                    buttonSounds = buttonSounds,
                    kokoroModel = kokoroModel,
                    kokoroThreads = kokoroThreads,
                    rate = rate,
                    pause = pause,
                    performance = performance,
                    speakWhileGenerating = speakWhileGenerating,
                    kokoroBucket = kokoroBucket,
                    incrementalSpeech = incrementalSpeech,
                    nativeModelVariant = nativeModelVariant,
                    modelChangeRunning = modelChangeRunning,
                    modelChangeError = modelChangeError,
                    benchmarkRunning = benchmarkRunning,
                    speechBackend = backend.backend
                ),
                actions = VoiceSettingsActions(
                    done = ::closeSettings,
                    selectProfile = { uid ->
                        cancelListeningIntent()
                        session.pause("profile_changed")
                        model.selectVoiceProfile(uid) { changed -> if (changed) closeSettings() }
                    },
                    installLocalModel = { voiceSettings.installRecognitionModel(recognitionConfiguration) },
                    cancelLocalModelInstall = { voiceSettings.cancelRecognitionModelInstall(recognitionConfiguration) },
                    removeLocalModel = {
                        session.pause("model_removed")
                        val retiring = recognitionConfiguration
                        localRecognition.releaseWarmModel { voiceSettings.removeRecognitionModel(retiring) }
                    },
                    changeLanguage = { tag, selected ->
                        session.recognitionConfigurationChanged()
                        language = tag
                        recognitionBackend = selected
                        savePreferences()
                    },
                    selectRecognitionModel = { selected ->
                        session.recognitionConfigurationChanged()
                        recognitionModel = selected
                        recognitionCompute = VoiceRecognitionCompute.CPU
                        recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
                        savePreferences()
                    },
                    selectRecognitionCompute = { selected ->
                        session.recognitionConfigurationChanged()
                        recognitionCompute = selected
                        savePreferences()
                    },
                    selectRecognitionThreads = { selected ->
                        session.recognitionConfigurationChanged()
                        recognitionThreads = selected
                        savePreferences()
                    },
                    changeReview = {
                        editBeforeSend = it
                        savePreferences()
                    },
                    changeCaptions = {
                        captions = it
                        savePreferences()
                    },
                    changeSpokenReplies = ::setSpokenReplies,
                    changeButtonSounds = {
                        buttonSounds = it
                        savePreferences()
                    },
                    selectKokoroThreads = {
                        audio.stop()
                        kokoroThreads = it
                        kokoroOutput?.configureThreads(it)
                        savePreferences()
                    },
                    installKokoroModel = voiceSettings::installKokoroModel,
                    cancelKokoroInstall = voiceSettings::cancelKokoroInstall,
                    removeKokoroModel = {
                        audio.stop()
                        kokoroOutput?.releaseWarmModel()
                        voiceSettings.removeKokoroModel()
                    },
                    selectNativeModelVariant = { selected ->
                        if (selected != nativeModelVariant && !modelChangeRunning) {
                            modelChangeRunning = true
                            modelChangeError = null
                            settingsScope.launch {
                                try {
                                    audio.stop()
                                    if (runtimeOwner.retireForModelChange()) {
                                        nativeModelVariant = selected
                                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, selected)
                                        savePreferences()
                                        voiceSettings.selectKokoroVariant(selected)
                                    } else {
                                        modelChangeError = "The previous speech engine has not retired. Wait and retry, or restart the app before changing models."
                                    }
                                } finally {
                                    modelChangeRunning = false
                                }
                            }
                        }
                    },
                    previewKokoro = ::previewSpeech,
                    changeRate = {
                        changeSpeechSettings(nextRate = it)
                        savePreferences()
                    },
                    changePause = {
                        pause = it
                        savePreferences()
                    },
                    changePerformance = {
                        performance = it
                        savePreferences()
                    },
                    useConversationalFast = {
                        changeSpeechSettings(nextRate = 1.25f)
                        pause = VoicePauseDuration.SHORT
                        performance = VoicePerformanceMode.FAST
                        speakWhileGenerating = true
                        savePreferences()
                    },
                    useNaturalSpeech = {
                        changeSpeechSettings(nextRate = STANDARD_SPEECH_RATE)
                        pause = VoicePauseDuration.NATURAL
                        performance = VoicePerformanceMode.AUTO
                        speakWhileGenerating = true
                        savePreferences()
                    },
                    changeSpeakWhileGenerating = {
                        speakWhileGenerating = it
                        savePreferences()
                    },
                    changeKokoroBucket = {
                        changeSpeechSettings(nextBucket = it)
                        savePreferences()
                    },
                    changeIncrementalSpeech = {
                        changeSpeechSettings(nextIncremental = it)
                        savePreferences()
                    }
                ),
                engineContent = {
                    OutlinedButton(onClick = {
                        cancelListeningIntent()
                        routeOpen = true
                    }) { Text("Audio output · $route") }
                    if (backend.backend.usesKokoroVoices) {
                        SynthesisLanguageChoices(synthesis, backend.backend.usesKokoroVoices, enabled = !session.state.awaitingReply, change = ::changeSynthesis)
                    }
                    VoiceSpeechBackendSettings(
                        value = backend,
                        selectBackend = {
                            audio.stop()
                            kokoroOutput?.releaseWarmModel()
                            backendOwner.selectBackend(it)
                        },
                        selectGateway = backendOwner::selectGateway,
                        recheckBackend = backendOwner::recheckBackend,
                        enabled = !benchmarkRunning && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
                    )
                    backend.backend.streamingEngine()?.let { engine ->
                        var streamingPreviewError by remember(engine) { mutableStateOf<String?>(null) }
                        StreamingVoiceModelCard(
                            engine = engine,
                            state = backend.streamingModels[engine],
                            standardPack = backend.streamingModels[MediaPipeSpeechEngine.KOKORO],
                            preparingNpuBase = backend.preparingNpuBase,
                            npuBaseError = backend.npuBaseError,
                            voiceId = backend.streamingVoiceId,
                            selectVoice = backendOwner::selectStreamingVoice,
                            enabled = backend.loaded && !benchmarkRunning && !state.awaitingReply && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING),
                            install = backendOwner::installStreamingModel,
                            importCompiled = backendOwner::importSm8750Model,
                            cancel = backendOwner::cancelStreamingInstall,
                            remove = {
                                audio.stop()
                                kokoroOutput?.releaseWarmModel()
                                backendOwner.removeStreamingModel()
                            },
                            preview = {
                                previewSpeech(kokoroVoice ?: KokoroVoice.HEART) { streamingPreviewError = it }
                            },
                            previewError = streamingPreviewError
                        )
                    }
                    if (backend.backend.isCompiledPocket) {
                        var pocketPreviewError by remember(backend.backend) { mutableStateOf<String?>(null) }
                        PocketCompiledModelCard(
                            state = backend.compiledPocketModel,
                            captionState = backend.pocketCaptionModel,
                            voiceId = backend.streamingVoiceId,
                            selectVoice = backendOwner::selectStreamingVoice,
                            enabled = backend.loaded && !backend.removingCompiledPocket && !benchmarkRunning && !state.awaitingReply && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING),
                            install = backendOwner::installStreamingModel,
                            cancel = backendOwner::cancelStreamingInstall,
                            remove = {
                                audio.stop()
                                kokoroOutput?.releaseWarmModel()
                                backendOwner.removeStreamingModel()
                            },
                            preview = { previewSpeech(kokoroVoice ?: KokoroVoice.HEART) { pocketPreviewError = it } },
                            previewError = pocketPreviewError
                        )
                    }
                },
                tuningContent = {
                    VoiceSpeechBenchmarkSettings(
                        voice = kokoroVoice,
                        streamingKokoroVoice = backend.streamingVoiceId ?: synthesis.voiceId,
                        threads = kokoroThreads,
                        speechRate = rate,
                        enabled = !benchmarkRunning && !state.awaitingReply && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING),
                        beforeStart = {
                            session.holdAudio(true)
                            session.pause("speech_benchmark_started")
                            awaitVoiceBenchmarkRetirement(
                                { completed -> localRecognition.releaseWarmModel(completed) },
                                { completed -> if (kokoroOutput == null) completed() else kokoroOutput.releaseWarmModel(completed) }
                            )
                        },
                        captionOffsetMs = tuning.captionOffsetMs,
                        viewModel = benchmarkOwner,
                        performance = { session.performanceHistory.snapshots.value },
                        feedback = { kokoroOutput?.synthesisFeedback?.value },
                        gateway = backend.factory.takeIf { backend.backend == VoiceSpeechBackend.GATEWAY_CUDA && it.selectedBackend == VoiceSpeechBackend.GATEWAY_CUDA }
                    )
                    if (!benchmarkRunning) {
                        VoiceTuningSettings(
                            tuning,
                            {
                                tuning = it
                                savePreferences()
                            },
                            session.performanceHistory,
                            kokoroOutput?.synthesisFeedback,
                            adjustCaptionOffset = { milliseconds ->
                                kokoroOutput?.calibrateCaptionOffset(milliseconds)
                                tuning = tuning.copy(captionOffsetMs = milliseconds)
                                savePreferences()
                            },
                            language = activeSpeechProfile()
                        )
                    }
                }
            )
        },
        transcript = {
            VoiceTranscriptScreen(
                model = model,
                profileName = profileName,
                status = status,
                session = state,
                captions = captions,
                controls = messageControls,
                spokenReplies = spokenReplies,
                changeSpokenReplies = ::setSpokenReplies,
                showVoice = { chatView = false },
                microphone = {
                    if (serviceReply != null) {
                        runtimeOwner.background.stop(serviceReply.token)
                        serviceReply.message?.runId?.let(model::stopVoiceTurn)
                    } else {
                        toggleMicrophone()
                    }
                },
                settings = {
                    cancelListeningIntent()
                    session.pause("settings_opened")
                    settingsOpen = true
                },
                end = ::leaveTranscript,
                destinations = destinations,
                bottomInteractionContent = bottomInteractionContent,
                captionCursor = session.captionCursors
            )
        },
        conversation = {
            VoiceConversationScreen(
                roomId = room.id,
                state = VoiceConversationUiState(
                    profileName = profileName,
                    profiles = profiles.filter { it.enabled },
                    selectedProfileId = selectedProfile?.uid,
                    captions = captions,
                    session = state,
                    status = status,
                    remaining = remaining,
                    localModel = localModel,
                    language = captureLanguage(),
                    recognitionReady = recognitionReady,
                    recognitionBackend = captureBackend(),
                    replyLanguage = activeSpeechProfile().tag,
                    recognitionConfiguration = recognitionConfiguration,
                    replyLanguageProfiles = backend.replyLanguageProfiles(synthesis),
                    translationMode = translationMode,
                    translationPair = translationPair,
                    translationModel = translationModel,
                    translationSetupNotice = translationSetupNotice,
                    enabled = enabled,
                    voiceReady = voiceReady && microphoneCuePrepared,
                    profileError = profilePreflightError,
                    preferencesApplied = preferencesApplied,
                    waitingApproval = waitingApproval,
                    draft = draft,
                    outputLevel = kokoroOutput?.outputLevel,
                    inputLevel = session.inputLevels,
                    captionCursor = session.captionCursors,
                    handsFreeInterruption = tuning.handsFreeInterruption,
                    interruptionState = audio.interruptionState
                ),
                actions = VoiceConversationActions(
                    selectProfile = { uid ->
                        cancelListeningIntent()
                        session.pause("profile_changed")
                        model.selectVoiceProfile(uid) {}
                    },
                    openSettings = {
                        cancelListeningIntent()
                        val currentState = session.states.value
                        val currentAdmission = model.voiceAdmission.value
                        if (currentState.shouldCheckpointDraft(currentAdmission?.requestId, currentAdmission?.stage)) model.checkpointVoiceDraft(currentState.transcript)
                        session.pause("settings_opened")
                        settingsOpen = true
                    },
                    showSettings = {
                        cancelListeningIntent()
                        settingsOpen = true
                    },
                    installLocalModel = { voiceSettings.installRecognitionModel(recognitionConfiguration) },
                    cancelLocalModelInstall = { voiceSettings.cancelRecognitionModelInstall(recognitionConfiguration) },
                    changeDraft = {
                        draft = it.take(8000)
                        session.editDraft(draft)
                    },
                    sendDraft = { session.sendDraft(draft) },
                    listen = { listen() },
                    recordAgain = {
                        session.discardDraft()
                        listen()
                    },
                    repair = { action ->
                        cancelListeningIntent()
                        session.pause("repair_settings_opened")
                        if (action == VoiceRecoveryAction.LOCAL_MODEL) settingsOpen = true else openVoiceRepairSettings(context, action)
                    },
                    openVoiceConfiguration = {
                        cancelListeningIntent()
                        session.pause("settings_opened")
                        settingsOpen = true
                    },
                    replay = { readResponse(VoiceMessageIdentity(retainedMessageId, retainedRunId, retainedRevision), state.reply) },
                    openChat = ::openChat,
                    toggleMicrophone = ::toggleMicrophone,
                    changeReplyLanguage = { tag ->
                        backend.selectReplyLanguage(tag, synthesis, backendOwner::selectStreamingVoice, ::changeSynthesis)
                    },
                    changeTranslationMode = { value ->
                        val reviewing = session.state.phase == VoicePhase.REVIEWING && session.state.hasDraft
                        cancelListeningIntent()
                        session.pause("translation_mode_changed")
                        translationMode = value
                        translationOutput = null
                        translationSetupNotice = null
                        if (!value) kokoroOutput?.configureEngine(backend.factory)
                        applyAudioConfiguration()
                        if (reviewing) session.resume()
                        savePreferences()
                    },
                    end = ::end,
                    changeTranslationPair = { selected ->
                        val reviewing = session.state.phase == VoicePhase.REVIEWING && session.state.hasDraft
                        cancelListeningIntent()
                        session.pause("translation_pair_changed")
                        translationPair = selected.normalized()
                        translationOutput = null
                        translationSetupNotice = null
                        applyAudioConfiguration()
                        if (reviewing) session.resume()
                        savePreferences()
                    },
                    installTranslationModel = backendOwner::installTranslationModel,
                    cancelTranslationModel = backendOwner::cancelTranslationModel
                )
            )
        },
        audioRoute = {
            VoiceRouteDialog(
                route = route,
                routes = systemAudio.routes(),
                speaker = state.speaker,
                dismiss = { routeOpen = false },
                selectRoute = { id ->
                    if (systemAudio.selectRoute(id)) route = systemAudio.routeName()
                    routeOpen = false
                },
                toggleSpokenReplies = { setSpokenReplies(!spokenReplies) }
            )
        }
    )
}

@Composable
private fun VoiceChatSessionPresentation(
    settingsOpen: Boolean,
    chatView: Boolean,
    routeOpen: Boolean,
    settings: @Composable () -> Unit,
    transcript: @Composable () -> Unit,
    conversation: @Composable () -> Unit,
    audioRoute: @Composable () -> Unit
) {
    if (settingsOpen) {
        settings()
    } else if (chatView) {
        transcript()
    } else {
        conversation()
    }
    if (routeOpen) audioRoute()
}

internal fun playKokoroPreview(output: KokoroOutput, voice: KokoroVoice, configure: () -> Unit = {}, text: String = SpeechLanguages.default.preview, error: (String, Boolean) -> Unit) {
    // A settings click can precede Compose's next SideEffect. Apply its rate before the
    // deferred output is initialized or compared with an SDK task's immutable rate.
    configure()
    // Settings previews do not belong to the previous conversation reply.
    output.bindDiagnostics(null)
    output.bindReply("")
    output.speak(text, voice, {}, error)
}

private data class VoiceSettingsUiState(
    val profiles: List<PlatformV2>,
    val activeProfiles: List<String>,
    val session: VoiceState,
    val saveError: String?,
    val localModel: LocalVoiceModelState,
    val language: String,
    val recognitionBackend: VoiceRecognitionBackend,
    val recognitionConfiguration: VoiceRecognitionConfiguration,
    val recognitionEvidence: Map<String, String>,
    val synthesis: VoiceSynthesisPreferences,
    val kokoroVoice: KokoroVoice?,
    val editBeforeSend: Boolean,
    val captions: Boolean,
    val spokenReplies: Boolean,
    val buttonSounds: Boolean,
    val kokoroModel: LocalVoiceModelState,
    val kokoroThreads: Int,
    val rate: Float,
    val pause: VoicePauseDuration,
    val performance: VoicePerformanceMode,
    val speakWhileGenerating: Boolean,
    val kokoroBucket: MediaPipeKokoroBucket,
    val incrementalSpeech: Boolean,
    val nativeModelVariant: KokoroModelVariant,
    val modelChangeRunning: Boolean,
    val modelChangeError: String?,
    val benchmarkRunning: Boolean = false,
    val speechBackend: VoiceSpeechBackend = VoiceSpeechBackend.DEFAULT
)

private class VoiceSettingsActions(
    val done: () -> Unit,
    val selectProfile: (String) -> Unit,
    val installLocalModel: () -> Unit,
    val cancelLocalModelInstall: () -> Unit,
    val removeLocalModel: () -> Unit,
    val changeLanguage: (String, VoiceRecognitionBackend) -> Unit,
    val selectRecognitionModel: (VoiceRecognitionModel) -> Unit,
    val selectRecognitionCompute: (VoiceRecognitionCompute) -> Unit,
    val selectRecognitionThreads: (Int) -> Unit,
    val changeReview: (Boolean) -> Unit,
    val changeCaptions: (Boolean) -> Unit,
    val changeSpokenReplies: (Boolean) -> Unit,
    val changeButtonSounds: (Boolean) -> Unit,
    val selectKokoroThreads: (Int) -> Unit,
    val installKokoroModel: () -> Unit,
    val cancelKokoroInstall: () -> Unit,
    val removeKokoroModel: () -> Unit,
    val selectNativeModelVariant: (KokoroModelVariant) -> Unit,
    val previewKokoro: (KokoroVoice, (String?) -> Unit) -> Unit,
    val changeRate: (Float) -> Unit,
    val changePause: (VoicePauseDuration) -> Unit,
    val changePerformance: (VoicePerformanceMode) -> Unit,
    val useConversationalFast: () -> Unit,
    val useNaturalSpeech: () -> Unit,
    val changeSpeakWhileGenerating: (Boolean) -> Unit,
    val changeKokoroBucket: (MediaPipeKokoroBucket) -> Unit,
    val changeIncrementalSpeech: (Boolean) -> Unit
)

@Composable
private fun VoiceSettingsScreen(
    state: VoiceSettingsUiState,
    actions: VoiceSettingsActions,
    engineContent: @Composable () -> Unit,
    tuningContent: @Composable () -> Unit
) {
    var previewError by remember(state.kokoroVoice) { mutableStateOf<String?>(null) }
    VoiceSettingsPage(actions.done) {
        if (state.benchmarkRunning) {
            Text("Speech benchmark is running. Playback and voice settings are paused until it finishes or you stop it.", style = MaterialTheme.typography.bodyMedium)
        } else if (state.modelChangeRunning) {
            Text("Finishing the previous speech engine before changing models…", style = MaterialTheme.typography.bodyMedium)
            CircularProgressIndicator()
        } else {
            VoiceChoice(
                "AI profile",
                state.activeProfiles.singleOrNull().orEmpty(),
                state.profiles.filter { it.enabled }.map {
                    it.uid to it.name.ifBlank { it.model }
                },
                actions.selectProfile,
                enabled = !state.session.awaitingReply
            )
            state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.modelChangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            VoiceSettingsSection("Speech recognition")
            VoiceRecognitionModelChoices(
                state.recognitionConfiguration.model,
                state.recognitionConfiguration.compute,
                state.recognitionConfiguration.threads,
                state.language,
                actions.selectRecognitionModel,
                actions.selectRecognitionCompute,
                actions.selectRecognitionThreads
            )
            if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
                LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, actions.removeLocalModel, showRemove = true, model = state.recognitionConfiguration.model, compute = state.recognitionConfiguration.compute)
            }
            RecognitionLanguageChoice(state.language, state.recognitionBackend, state.recognitionConfiguration.model, actions.changeLanguage)
            val actualRecognition = state.recognitionEvidence["recognitionActualProvider"]
            if (state.recognitionEvidence["recognitionFailure"] in setOf("ASR_RETIREMENT_UNCONFIRMED", "ASR_RETIREMENT_TIMEOUT")) {
                Text("The previous recognizer has not finished releasing its model. Restart the app if it cannot finish; the model remains protected from removal.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.recognitionEvidence["recognitionFailure"] == "ASR_RETIREMENT_SUPERSEDED") {
                Text("Recognition selection changed before model cleanup completed. Select the model again to retry removal.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.recognitionEvidence["recognitionExecutionVerified"] == "true" && actualRecognition != null) {
                val processor = when (actualRecognition.lowercase()) {
                    "cpu" -> "CPU"
                    "qnn-gpu" -> "Qualcomm GPU"
                    "qnn-gpu+cpu" -> "Qualcomm GPU with CPU operations"
                    "qnn-htp-context" -> "Qualcomm NPU"
                    else -> actualRecognition
                }
                Text("Last recognition executed on $processor.", style = MaterialTheme.typography.bodySmall)
            }
            VoiceSettingsSection("Conversation")
            Text("Replies begin with a short spoken answer. Longer details stay available in chat.", style = MaterialTheme.typography.bodySmall)
            VoiceSettingSwitch("Review before sending", state.editBeforeSend, actions.changeReview)
            VoiceSettingSwitch("Live captions", state.captions, actions.changeCaptions)
            VoiceSettingSwitch("Spoken replies", state.spokenReplies, actions.changeSpokenReplies)
            VoiceSettingSwitch("Button sounds", state.buttonSounds, actions.changeButtonSounds)
            VoiceSettingsSection("Playback")
            engineContent()
            if (!state.speechBackend.isStreamingLocal) {
                if (state.speechBackend != VoiceSpeechBackend.GATEWAY_CUDA) {
                    VoiceChoice("Native Kokoro model", state.nativeModelVariant.name, KokoroModelVariant.entries.map { it.name to it.displayName }, { actions.selectNativeModelVariant(KokoroModelVariant.valueOf(it)) })
                    Text("Each model is installed separately. Original FP32 uses more storage and memory; compare CPU, GPU and NPU with the same selected model before choosing an accelerator.", style = MaterialTheme.typography.bodySmall)
                }
                KokoroPlaybackCard(
                    state = state.kokoroModel,
                    voice = state.kokoroVoice,
                    threads = state.kokoroThreads,
                    english = state.synthesis.language.startsWith("en-"),
                    previewError = previewError,
                    selectThreads = actions.selectKokoroThreads,
                    install = actions.installKokoroModel,
                    cancel = actions.cancelKokoroInstall,
                    remove = {
                        previewError = null
                        actions.removeKokoroModel()
                    },
                    preview = { selected -> actions.previewKokoro(selected) { previewError = it } },
                    showThreads = state.speechBackend.supportsCpuThreadCalibration
                )
            }
            if (state.speechBackend.isCompiledPocket) {
                VoiceChoice(
                    "Pocket decoder CPU threads",
                    state.kokoroThreads.toString(),
                    listOf("0" to "Default · ${VoiceCpuSchedulingPolicy.initial(0)}") + (1..VoiceCpuSchedulingPolicy.deviceLimit()).map { it.toString() to it.toString() },
                    { actions.selectKokoroThreads(it.toInt()) }
                )
                Text("This setting controls the decoder transformer. Pocket streams audio for each phrase while the next reply text is prepared.", style = MaterialTheme.typography.bodySmall)
            } else if (state.speechBackend.isStreamingLocal) {
                VoiceSettingSwitch("Keep one speech stream for each reply", state.incrementalSpeech, actions.changeIncrementalSpeech)
                Text("New text joins the current stream as it arrives. The full voice benchmark measures both input modes.", style = MaterialTheme.typography.bodySmall)
                if (state.speechBackend.streamingEngine()?.isKokoro == true) {
                    VoiceChoice("Kokoro phrase capacity · experiment", state.kokoroBucket.name, MediaPipeKokoroBucket.entries.map { it.name to it.label }, { actions.changeKokoroBucket(MediaPipeKokoroBucket.valueOf(it)) })
                    Text("Compare capacities with the same voice and text. Model default lets the runtime choose; a smaller capacity may help short phrases. The complete reply remains queued.", style = MaterialTheme.typography.bodySmall)
                }
            }
            var sliderRate by remember(state.rate) { androidx.compose.runtime.mutableFloatStateOf(state.rate) }
            Text("Speech rate · ${String.format(LocalLocale.current.platformLocale, "%.2f", sliderRate)}×")
            Text("Speech rate changes how quickly the voice talks. Performance mode changes how soon speech starts.", style = MaterialTheme.typography.bodySmall)
            if (state.speechBackend.isCompiledPocket) {
                Text("Streaming speed preserves the voice's pitch. With live captions on, each short phrase waits for local alignment of its adjusted audio. Unmatched words remain readable without a timed highlight.", style = MaterialTheme.typography.bodySmall)
            } else if (state.speechBackend.isStreamingLocal) {
                Text("Streaming speed preserves the voice's pitch. Captions follow the adjusted audio timing.", style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = sliderRate,
                onValueChange = { sliderRate = it },
                valueRange = .5f..2f,
                steps = 29,
                onValueChangeFinished = {
                    val snapped = Math.round(sliderRate * 20) / 20f
                    sliderRate = snapped
                    if (snapped != state.rate) actions.changeRate(snapped)
                }
            )
            OutlinedButton(onClick = { actions.changeRate(STANDARD_SPEECH_RATE) }, enabled = state.rate != STANDARD_SPEECH_RATE) { Text("Reset to 1×") }
            VoiceChoice("Pause duration", state.pause.name, VoicePauseDuration.entries.map { it.name to it.label }, { actions.changePause(VoicePauseDuration.valueOf(it)) })
            VoiceChoice("Performance mode", state.performance.name, VoicePerformanceMode.entries.map { it.name to it.label }, { actions.changePerformance(VoicePerformanceMode.valueOf(it)) })
            VoiceSettingSwitch("Speak while the reply is generating", state.speakWhileGenerating, actions.changeSpeakWhileGenerating)
            OutlinedButton(onClick = actions.useNaturalSpeech) { Text("Use Natural Speech") }
            OutlinedButton(onClick = actions.useConversationalFast) { Text("Use Conversational Fast") }
        }
        tuningContent()
    }
}

@Composable
internal fun VoiceChoice(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Select $label", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { (key, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        expanded = false
                        onSelect(key)
                    })
                }
            }
        }
    }
}

@Composable
internal fun VoiceSettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked, change)
    }
}

@Composable
private fun VoiceRouteDialog(
    route: String,
    routes: List<Pair<Int, String>>,
    speaker: Boolean,
    dismiss: () -> Unit,
    selectRoute: (Int) -> Unit,
    toggleSpokenReplies: () -> Unit
) {
    val context = LocalContext.current
    dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Audio output", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(route, color = MaterialTheme.colorScheme.onSurfaceVariant)
                routes.forEach { (id, name) ->
                    TextButton(onClick = { selectRoute(id) }) { Text(name) }
                }
                TextButton(onClick = toggleSpokenReplies) { Text(if (speaker) "Turn spoken replies off" else "Turn spoken replies on") }
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }) { Text("Android sound settings") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}

@Composable
internal fun VoiceSettingsPage(onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("voice_settings_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to voice chat") }
            Text("Voice settings", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDone) { Text("Done") }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun VoiceSettingsSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
