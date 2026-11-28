package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngineFactory

/** Restoring a screen must not replace the retained reply's engine with unloaded defaults. */
@Composable
internal fun VoiceOutputConfigurationEffect(preferencesApplied: Boolean, output: KokoroOutput?, factory: VoicePhraseEngineFactory) {
    LaunchedEffect(output, factory, preferencesApplied) {
        if (preferencesApplied) output?.configureEngine(factory)
    }
}
