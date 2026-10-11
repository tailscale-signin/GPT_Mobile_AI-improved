package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import dev.chungjungsoo.gptmobile.data.dto.SavedThemeProfile
import dev.chungjungsoo.gptmobile.data.dto.ThemePresets
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeViewModel
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppEditorScaffold
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppPanel
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppSectionTitle
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.theme.RenovationPalettes
import dev.chungjungsoo.gptmobile.presentation.theme.customPaletteColorScheme
import dev.chungjungsoo.gptmobile.presentation.theme.paletteContrastChecks
import dev.chungjungsoo.gptmobile.presentation.theme.suggestPaletteAccent
import dev.chungjungsoo.gptmobile.util.getThemeModeTitle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val themeDraftSaver = Saver<ThemeSetting, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<ThemeSetting>(it) }.getOrNull() }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(onDismiss: () -> Unit, asDialog: Boolean = true, initialTab: Int = 0) {
    val viewModel = LocalThemeViewModel.current
    val saved by viewModel.themeSetting.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = themeDraftSaver) { mutableStateOf(saved) }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var valid by rememberSaveable { mutableStateOf(true) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    if (saveError != null) dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
        onDismissRequest = { saveError = null },
        title = { Text("Appearance was not saved") },
        text = { Text(saveError.orEmpty()) },
        confirmButton = { TextButton(onClick = { saveError = null }) { Text("Keep editing") } }
    )
    val body: @Composable () -> Unit = {
        AppEditorScaffold(
            title = "Appearance",
            dirty = draft != saved,
            onCancel = onDismiss,
            onSave = {
                saving = true
                viewModel.applyDraft(draft, onError = {
                    saveError = it
                    saving = false
                }) {
                    saving = false
                    onDismiss()
                }
            },
            saveEnabled = valid && !saving,
            saveLabel = if (saving) "Applying…" else "Apply theme"
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SettingsTabs(listOf("Theme", "Colour", "Display"), tab) { tab = it }
                GPTMobileTheme(
                    dynamicTheme = draft.dynamicTheme,
                    themeMode = draft.themeMode,
                    customPrimaryArgb = draft.customPrimaryArgb,
                    customPalette = draft.customPalette,
                    themeFamily = draft.themeFamily,
                    updateSystemBars = false
                ) {
                    AppPanel {
                        Text("Live preview", style = MaterialTheme.typography.labelLarge)
                        AppSectionTitle("Your conversations")
                        Text("A quieter surface. A clearer focus.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.large) { Text("New chat", Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("Selected item", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                when (tab) {
                    0 -> {
                        AppSectionTitle("Colour family")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RenovationPalettes.names.forEach { name ->
                                FilterChip(
                                    selected = draft.themeFamily == name && draft.dynamicTheme == DynamicTheme.OFF,
                                    onClick = { draft = draft.copy(themeFamily = name, dynamicTheme = DynamicTheme.OFF) },
                                    label = { Text(name) }
                                )
                            }
                        }
                        Text("Each family includes coordinated light and dark colours. Your previous custom palette is kept.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ThemeModePicker(draft.themeMode) { draft = draft.copy(themeMode = it) }
                        ThemeSwitch("Wallpaper colours", "Use your system palette", draft.dynamicTheme == DynamicTheme.ON) {
                            draft = draft.copy(dynamicTheme = if (it) DynamicTheme.ON else DynamicTheme.OFF)
                        }
                        AppSectionTitle("Saved and classic themes")
                        (draft.savedProfiles + ThemePresets.profiles).forEach { profile ->
                            val selected = draft.dynamicTheme == DynamicTheme.OFF && draft.themeFamily == null && draft.customPalette == profile.palette
                            Surface(
                                onClick = { draft = draft.copy(dynamicTheme = DynamicTheme.OFF, themeFamily = null, themeMode = profile.mode, customPalette = profile.palette, customPrimaryArgb = profile.palette.primary) },
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.large
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        if (selected) Icon(Icons.Rounded.Check, "Selected")
                                    }
                                    Text(getThemeModeTitle(profile.mode), style = MaterialTheme.typography.bodyMedium)
                                    if (profile in draft.savedProfiles) {
                                        TextButton(onClick = {
                                            draft = draft.copy(savedProfiles = draft.savedProfiles.filterNot { it.name == profile.name })
                                        }) { Text("Remove saved theme") }
                                    }
                                }
                            }
                        }
                    }
                    1 -> ThemeDraftColourEditor(draft, onChange = { draft = it }, onValid = { valid = it })
                    2 -> {
                        ThemeModePicker(draft.themeMode) { draft = draft.copy(themeMode = it) }
                        ThemeSwitch("Reduce motion", "Use immediate transitions and minimise decorative movement", draft.reducedMotion) { draft = draft.copy(reducedMotion = it) }
                        Text("Conversation density, streaming, edge fades and response animation remain available in Conversations → Reading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = {
                    draft = draft.copy(dynamicTheme = DynamicTheme.OFF, themeFamily = "Forest", themeMode = ThemeMode.SYSTEM)
                    valid = true
                }) { Text("Preview recommended defaults") }
            }
        }
    }
    if (asDialog) {
        FadingDialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) { body() }
    } else {
        body()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeModePicker(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { onChange(item) }, label = { Text(getThemeModeTitle(item)) }) }
    }
}

@Composable
private fun ThemeSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeDraftColourEditor(draft: ThemeSetting, onChange: (ThemeSetting) -> Unit, onValid: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val initial = draft.customPalette ?: CustomThemePalette(scheme.primary.toArgb().toLong() and 0xFFFFFFFF, scheme.secondary.toArgb().toLong() and 0xFFFFFFFF, scheme.background.toArgb().toLong() and 0xFFFFFFFF, scheme.surface.toArgb().toLong() and 0xFFFFFFFF)
    var values by rememberSaveable { mutableStateOf(listOf(initial.primary, initial.secondary, initial.background, initial.surface).map { "%08X".format(it and 0xFFFFFFFF) }) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    val labels = listOf("Accent", "Secondary", "Background", "Surface")
    val parsed = values.map { it.removePrefix("#").takeIf { hex -> hex.length == 6 || hex.length == 8 }?.toLongOrNull(16)?.let { color -> if (it.removePrefix("#").length == 6) color or 0xFF000000 else color } }
    val candidate = if (parsed.all { it != null }) CustomThemePalette(parsed[0]!!, parsed[1]!!, parsed[2]!!, parsed[3]!!) else null
    fun update(next: List<String>) {
        values = next
        val colours = next.map { it.removePrefix("#").takeIf { hex -> hex.length == 6 || hex.length == 8 }?.toLongOrNull(16)?.let { colour -> if (it.removePrefix("#").length == 6) colour or 0xFF000000 else colour } }
        onValid(colours.all { it != null })
        if (colours.all { it != null }) onChange(draft.copy(themeFamily = null, dynamicTheme = DynamicTheme.OFF, customPalette = CustomThemePalette(colours[0]!!, colours[1]!!, colours[2]!!, colours[3]!!), customPrimaryArgb = colours[0]))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text(label) }) }
    }
    OutlinedTextField(values[selected], { text -> update(values.toMutableList().apply { set(selected, text.take(9)) }) }, label = { Text("${labels[selected]} · ARGB or RGB hex") }, isError = parsed[selected] == null, singleLine = true, modifier = Modifier.fillMaxWidth())
    ColorWheelPicker(parsed[selected]?.let(::Color) ?: scheme.primary) { colour -> update(values.toMutableList().apply { set(selected, "%08X".format(colour.toArgb())) }) }
    if (candidate != null) {
        val checks = paletteContrastChecks(customPaletteColorScheme(scheme, candidate))
        val failures = checks.filterNot { it.passes }
        Text(if (failures.isEmpty()) "Reference contrast checks pass" else "Review colour contrast", style = MaterialTheme.typography.titleMedium)
        failures.forEach { Text("${it.role}: %.2f:1 (target %.1f:1)".format(it.ratio, it.minimum), color = scheme.error) }
        if (failures.isNotEmpty()) {
            TextButton(onClick = {
                val suggested = suggestPaletteAccent(candidate)
                update(listOf(suggested.primary, suggested.secondary, suggested.background, suggested.surface).map { "%08X".format(it and 0xFFFFFFFF) })
            }) { Text("Suggest readable accents") }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Save a named theme") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = name.isNotBlank(), onClick = {
            val profile = SavedThemeProfile(name.trim(), candidate, draft.themeMode)
            onChange(draft.copy(savedProfiles = draft.savedProfiles.filterNot { it.name.equals(profile.name, true) } + profile))
            name = ""
        }) { Text("Add to saved themes on Apply") }
    }
}
