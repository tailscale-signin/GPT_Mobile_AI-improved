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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeViewModel
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.util.getThemeModeTitle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen(onDismiss: () -> Unit) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    val viewModel = LocalThemeViewModel.current
    val settings by viewModel.themeSetting.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = { Text("Themes & appearance") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                })
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsHero("Appearance studio", "Set the mood", "")
                SettingsTabs(listOf("Gallery", "Create", "Display"), tab) { tab = it }
                if (tab == 0) {
                    val profiles = dev.chungjungsoo.gptmobile.data.dto.ThemePresets.profiles + settings.savedProfiles
                    profiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { profile ->
                                androidx.compose.material3.Card(
                                    onClick = { viewModel.applyProfile(profile) },
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(profile.palette.background)),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(profile.palette.primary, profile.palette.secondary, profile.palette.surface).forEach { color ->
                                                androidx.compose.material3.Surface(color = androidx.compose.ui.graphics.Color(color), shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.weight(1f)) {
                                                    Text(" ", Modifier.padding(vertical = 8.dp))
                                                }
                                            }
                                        }
                                        val foreground = if (androidx.core.graphics.ColorUtils.calculateLuminance(profile.palette.background.toInt()) > 0.179) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
                                        Text(profile.name, color = foreground, style = MaterialTheme.typography.titleSmall)
                                        if (profile in settings.savedProfiles) {
                                            androidx.compose.material3.TextButton(onClick = { viewModel.deleteProfile(profile.name) }) {
                                                Text("Delete", color = foreground)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    androidx.compose.material3.TextButton(onClick = { viewModel.updateCustomPalette(null) }) { Text("System palette") }
                }
                if (tab == 1) CustomPaletteEditor(customizeOnly = true)
                if (tab == 2) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(selected = settings.themeMode == mode, onClick = { viewModel.updateThemeMode(mode) }, label = { Text(getThemeModeTitle(mode)) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Wallpaper colours", style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(checked = settings.dynamicTheme == DynamicTheme.ON, onCheckedChange = {
                            viewModel.updateDynamicTheme(if (it) DynamicTheme.ON else DynamicTheme.OFF)
                        })
                    }
                }
            }
        }
    }
}
