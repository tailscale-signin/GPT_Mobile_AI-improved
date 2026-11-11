package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction

internal fun voiceRepairLabel(action: VoiceRecoveryAction): String = when (action) {
    VoiceRecoveryAction.MICROPHONE_PERMISSION -> "Review microphone permission"
    VoiceRecoveryAction.SPEECH_SERVICE -> "Review Android speech service"
    VoiceRecoveryAction.SPEECH_LANGUAGE -> "Review speech language"
    VoiceRecoveryAction.LOCAL_MODEL -> "Review local speech model"
}

internal fun voiceRepairIntent(action: VoiceRecoveryAction, packageName: String): Intent? = when (action) {
    VoiceRecoveryAction.MICROPHONE_PERMISSION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    VoiceRecoveryAction.SPEECH_SERVICE, VoiceRecoveryAction.SPEECH_LANGUAGE -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    VoiceRecoveryAction.LOCAL_MODEL -> null
}

internal fun openVoiceRepairSettings(context: Context, action: VoiceRecoveryAction) {
    val intent = voiceRepairIntent(action, context.packageName) ?: return
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}
