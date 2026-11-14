package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
import dev.chungjungsoo.gptmobile.presentation.common.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun NavGraphBuilder.voiceNavigation(navController: NavHostController, onReportDownload: () -> Unit = {}, origin: () -> Offset) {
    composable(Route.VOICE_ENTRY, enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(150)) }) {
        val model: VoiceEntryViewModel = hiltViewModel()
        val state by model.state.collectAsStateWithLifecycle()
        val modelPreparation by model.gatewayModelPreparation.collectAsStateWithLifecycle()
        LaunchedEffect(state.route) {
            state.route?.let { route -> navController.navigate(route) { popUpTo(Route.VOICE_ENTRY) { inclusive = true } } }
        }
        val voiceSettings: VoicePreferencesViewModel = hiltViewModel()
        val stores by voiceSettings.modelStores.collectAsStateWithLifecycle()
        val moonshine = stores?.moonshine?.state?.collectAsStateWithLifecycle()?.value
        val kokoro = stores?.kokoro?.state?.collectAsStateWithLifecycle()?.value
        val modelsReady = moonshine?.status == LocalVoiceModelStatus.READY && kokoro?.status == LocalVoiceModelStatus.READY
        val context = LocalContext.current
        val installation by VoicePluginInstall.state.collectAsStateWithLifecycle()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(context, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    VoicePluginInstall.refresh(context)
                    delay(5_000)
                }
            }
        }
        VoiceEntryScreen(
            state,
            origin(),
            model::select,
            { if (modelsReady) model.start(enable = true) },
            { navController.navigateUp() },
            { navController.navigate(Route.AI_PLATFORMS) },
            modelPreparation = modelPreparation[state.selected],
            recheckModel = { model.recheckGatewayModel(state.selected) },
            modelsReady = modelsReady,
            setup = {
                if (moonshine != null && kokoro != null) {
                    VoicePluginSetup(moonshine, kokoro, installation, { VoicePluginInstall.start(context) }, { VoicePluginInstall.cancel(context) }) {
                        VoicePluginInstall.refresh(context)
                        VoicePluginInstall.captureReportObservation(moonshine.status, kokoro.status)
                        onReportDownload()
                    }
                }
            }
        )
    }
    composable(
        Route.VOICE_CHAT,
        arguments = listOf(navArgument("chatRoomId") { type = NavType.IntType }, navArgument("enabledPlatforms") { defaultValue = "" }),
        enterTransition = { fadeIn(tween(280)) },
        exitTransition = { fadeOut(tween(220)) }
    ) { entry ->
        val id = entry.arguments?.getInt("chatRoomId") ?: 0
        VoiceChatScreen(
            onEnd = { navController.navigateUp() },
            onOpenChat = { navController.navigate("chat_room/$id") { popUpTo(Route.VOICE_CHAT) { inclusive = true } } }
        )
    }
}

@androidx.compose.runtime.Composable
internal fun VoicePluginSetup(
    recognition: dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState,
    playback: dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState,
    installation: dev.chungjungsoo.gptmobile.data.voice.VoiceInstallState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReportDownload: () -> Unit = {}
) {
    val context = LocalContext.current
    val total = recognition.totalBytes + playback.totalBytes
    val received = recognition.downloadedBytes + playback.downloadedBytes
    val checking = recognition.status == LocalVoiceModelStatus.CHECKING || playback.status == LocalVoiceModelStatus.CHECKING
    val downloading = recognition.status == LocalVoiceModelStatus.DOWNLOADING || playback.status == LocalVoiceModelStatus.DOWNLOADING
    val message = recognition.message ?: playback.message
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val ready = recognition.status == LocalVoiceModelStatus.READY && playback.status == LocalVoiceModelStatus.READY
    if (ready) {
        androidx.compose.material3.Text("Voice Chat plugin ready", color = accent)
    } else if (installation.pending && !downloading || checking) {
        androidx.compose.material3.LinearProgressIndicator(color = accent)
        androidx.compose.material3.Text(
            if (checking) "Checking voice files…" else installation.waitingMessage(),
            color = accent
        )
        installation.elapsedMillis?.takeIf { it >= 30_000 && installation.pending }?.let {
            androidx.compose.material3.Text("${it / 1_000} seconds since this download was requested. Include Voice diagnostics if you report the delay.")
        }
        if (installation.pending) androidx.compose.material3.TextButton(onClick = onCancel) { androidx.compose.material3.Text("Cancel download") }
    } else if (downloading) {
        val active = if (playback.status == LocalVoiceModelStatus.DOWNLOADING) playback else recognition
        VoiceTransferProgress(active.transferStage, received, total)
        androidx.compose.material3.Text("${android.text.format.Formatter.formatShortFileSize(context, received)} of ${android.text.format.Formatter.formatShortFileSize(context, total)}")
        androidx.compose.material3.TextButton(onClick = onCancel) { androidx.compose.material3.Text("Cancel download") }
    } else {
        val remaining = listOf(recognition, playback).filter { it.status != LocalVoiceModelStatus.READY }.sumOf { it.totalBytes }
        androidx.compose.material3.Text("English · ${android.text.format.Formatter.formatShortFileSize(context, remaining)}", color = accent)
        if (installation.failure != null && message == null) {
            androidx.compose.material3.Text(
                if (installation.failure == "WORK_NOT_FOUND") "Android no longer has this download. Start it again to resume verified files." else "Voice download could not start or finish. Tap to try again.",
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }
        message?.let { androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        androidx.compose.material3.Button(onClick = onStart, enabled = !checking) {
            androidx.compose.material3.Text(if (message == null && installation.failure == null) "Download Voice Chat plugin" else "Resume download")
        }
    }
    if (!ready && (installation.pending || installation.failure != null || downloading || installation.phase == "COMPLETED")) {
        androidx.compose.material3.TextButton(onClick = onReportDownload) { androidx.compose.material3.Text("Report download problem") }
    }
}
