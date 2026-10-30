package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.common.AppHaptic
import dev.chungjungsoo.gptmobile.presentation.common.ThemeViewModel
import dev.chungjungsoo.gptmobile.presentation.common.rememberAppHaptic

@Composable
internal fun AccessibilitySettingsPanel() {
    val model: ThemeViewModel = hiltViewModel()
    val settings by model.themeSetting.collectAsStateWithLifecycle()
    val haptic = rememberAppHaptic()
    var textScale by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
    var strength by remember(settings.hapticStrength) { mutableFloatStateOf(settings.hapticStrength) }
    Column {
        Text("Readability and feedback", style = MaterialTheme.typography.titleMedium)
        Row {
            Text("Larger text for short answers", Modifier.weight(1f))
            Switch(settings.adaptiveAnswerText, { model.updateAccessibility(adaptiveAnswers = it) }, modifier = Modifier.semantics { contentDescription = "Larger text for short answers" })
        }
        Text("Long answers keep your regular text size. Changes apply across conversations.")
        Text("Text size: ${(textScale * 100).toInt()}% of your system setting")
        Slider(textScale, { textScale = it }, onValueChangeFinished = { model.updateAccessibility(textScale = textScale) }, valueRange = 1f..1.5f, steps = 4, modifier = Modifier.semantics { contentDescription = "Additional text size" })
        Row {
            Text("Haptic feedback", Modifier.weight(1f))
            Switch(settings.hapticsEnabled, { model.updateAccessibility(enabled = it) }, modifier = Modifier.semantics { contentDescription = "Haptic feedback" })
        }
        Text("Feedback strength")
        Slider(strength, { strength = it }, onValueChangeFinished = { model.updateAccessibility(strength = strength) }, enabled = settings.hapticsEnabled, valueRange = 0.1f..1f, modifier = Modifier.semantics { contentDescription = "Haptic feedback strength" })
        Row {
            TextButton(onClick = { haptic(AppHaptic.SUCCESS) }) { Text("Success") }
            TextButton(onClick = { haptic(AppHaptic.ERROR) }) { Text("Error") }
            TextButton(onClick = { haptic(AppHaptic.CONFIRMATION) }) { Text("Confirm") }
        }
        val render by dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatRuntimeHealth.render.collectAsStateWithLifecycle()
        val memory by dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatRuntimeHealth.memory.collectAsStateWithLifecycle()
        Text("Chat diagnostics · this session", style = MaterialTheme.typography.titleSmall)
        Text("Formatting: ${render.completed} completed · ${render.failed} fallback events")
        render.durationsMs.sorted().takeIf { it.isNotEmpty() }?.let { durations ->
            Text("Median formatting time: ${durations[durations.size / 2]} ms · last ${durations.size} events")
        }
        if (memory.maxHeapBytes > 1) Text("Heap: ${memory.usedHeapBytes / 1_048_576} / ${memory.maxHeapBytes / 1_048_576} MB · ${memory.level}")
        Text("Previous themes", style = MaterialTheme.typography.titleSmall)
        Text("${settings.themeHistory.size} saved changes", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        settings.themeHistory.asReversed().forEach { snapshot ->
            TextButton(onClick = { model.restoreTheme(snapshot) }) {
                Text("Restore ${snapshot.mode.name.lowercase()} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(snapshot.savedAt))}")
            }
        }
        if (settings.themeHistory.isNotEmpty()) TextButton(onClick = model::clearThemeHistory) { Text("Clear theme history") }
    }
}
