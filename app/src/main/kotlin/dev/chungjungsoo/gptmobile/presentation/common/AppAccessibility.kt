package dev.chungjungsoo.gptmobile.presentation.common

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting

internal data class AccessibilityOptions(val haptics: Boolean = true, val strength: Float = 0.5f)
internal val LocalAccessibilityOptions = staticCompositionLocalOf { AccessibilityOptions() }
internal enum class AppHaptic { SUCCESS, ERROR, CONFIRMATION, NAVIGATION }

@Composable
internal fun AccessibilityProvider(settings: ThemeSetting, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val original = LocalHapticFeedback.current
    val haptic = remember(original, settings.hapticsEnabled) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (settings.hapticsEnabled) original.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
    CompositionLocalProvider(
        LocalDensity provides if (settings.textScale == 1f) density else Density(density.density, density.fontScale * settings.textScale.coerceIn(1f, 1.5f)),
        LocalHapticFeedback provides haptic,
        dev.chungjungsoo.gptmobile.presentation.ui.chat.LocalAdaptiveAnswerText provides settings.adaptiveAnswerText,
        LocalAccessibilityOptions provides AccessibilityOptions(settings.hapticsEnabled, settings.hapticStrength),
        content = content
    )
}

@Composable
internal fun rememberAppHaptic(): (AppHaptic) -> Unit {
    val context = LocalContext.current.applicationContext
    val options = LocalAccessibilityOptions.current
    return remember(context, options) {
        { signal ->
            if (options.haptics && Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator?.hasVibrator() == true) {
                    val timing = when (signal) {
                        AppHaptic.SUCCESS -> longArrayOf(0, 18, 35, 18)
                        AppHaptic.ERROR -> longArrayOf(0, 40, 45, 40)
                        AppHaptic.CONFIRMATION -> longArrayOf(0, 25)
                        AppHaptic.NAVIGATION -> longArrayOf(0, 12)
                    }
                    val strength = if (vibrator.hasAmplitudeControl()) (255 * options.strength.coerceIn(0.1f, 1f)).toInt() else VibrationEffect.DEFAULT_AMPLITUDE
                    val amplitudes = IntArray(timing.size) { if (it % 2 == 0) 0 else strength }
                    runCatching { vibrator.vibrate(VibrationEffect.createWaveform(timing, amplitudes, -1)) }
                }
            }
        }
    }
}
