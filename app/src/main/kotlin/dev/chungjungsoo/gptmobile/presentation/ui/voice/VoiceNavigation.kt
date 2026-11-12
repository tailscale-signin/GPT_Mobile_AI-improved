package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.presentation.common.Route

internal fun NavGraphBuilder.voiceNavigation(navController: NavHostController, origin: () -> Offset) {
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
        LaunchedEffect(state.loaded, state.selected, state.enabled, modelsReady) {
            if (modelsReady && state.loaded && state.enabled && state.selected.isNotBlank() && state.error == null) model.start()
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
                if (!modelsReady && moonshine != null && kokoro != null) {
                    LocalVoiceSetupCard(moonshine, voiceSettings::installLocalModel, voiceSettings::cancelLocalModelInstall, {}, compact = true)
                    KokoroPlaybackCard(
                        kokoro, null, 2, true, null, {}, {}, voiceSettings::installKokoroModel,
                        voiceSettings::cancelKokoroInstall, {}, {}, setup = true
                    )
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
