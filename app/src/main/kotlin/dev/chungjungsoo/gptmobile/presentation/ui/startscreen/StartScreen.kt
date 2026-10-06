package dev.chungjungsoo.gptmobile.presentation.ui.startscreen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.ThemedAppIcon
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BackupRestoreHost
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingViewModelV2

@Composable
fun StartScreen(onStartClick: () -> Unit, onRestored: () -> Unit = {}, viewModel: SettingViewModelV2 = hiltViewModel()) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var more by rememberSaveable { mutableStateOf(false) }
    val titles = listOf("Your AI. Your way.", "Pick a place to start", "A conversation that keeps up", "Bring your world with you")
    val descriptions = listOf(
        "Ask, explore and create with the AI you choose. A few simple steps will get you ready.",
        "Start with one model. You can add more whenever you like.",
        "Write naturally. Add a follow-up while your AI works, or choose tools from the conversation controls.",
        "Keep your conversations, connections and preferences together with Backup & Restore."
    )
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("GPT MOBILE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onStartClick) { Text("Skip tour") }
            }
            AnimatedContent(page, modifier = Modifier.weight(1f).fillMaxWidth(), label = "introPage") { step ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (step == 0) {
                        ThemedAppIcon(Modifier.size(96.dp))
                    } else {
                        Icon(
                            when (step) {
                                1 -> Icons.Rounded.SmartToy
                                2 -> Icons.Rounded.ChatBubble
                                else -> Icons.Rounded.Backup
                            },
                            null,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    Text(titles[step], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(descriptions[step], style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (step) {
                        0 -> {
                            TourTile(Icons.Rounded.ChatBubble, "New here?", "Start fresh with a guided AI setup.")
                            OutlinedButton(onClick = viewModel::openBackupRestoreDialog, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Restore, null, Modifier.padding(end = 8.dp))
                                Text("I have a backup")
                            }
                        }
                        1 -> {
                            TourTile(Icons.Rounded.SmartToy, "Try Free", "A quick way to explore without adding an API key.")
                            TourTile(Icons.Rounded.Cloud, "Connect an online provider", "Use an account or API key you already have.")
                            TourTile(Icons.Rounded.Memory, "Run on your device", "Download a supported local model when you're ready.")
                        }
                        2 -> {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Help me plan my next project", style = MaterialTheme.typography.titleMedium)
                                    Text("Add a follow-up: “Keep it within my budget.”", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Surface(onClick = { more = !more }, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Rounded.Tune, null)
                                    Text("A little more control", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (more) "Collapse options" else "Expand options")
                                }
                            }
                            AnimatedVisibility(more) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TourTile(Icons.Rounded.Tune, "Tools", "Choose search, location and plugins for each model.")
                                    TourTile(Icons.Rounded.Memory, "Memory", "Manage what is saved and recalled in Plugins/Tools.")
                                    TourTile(Icons.Rounded.SmartToy, "Delegation", "Let a helper do the work, then have a reviewer check it.")
                                }
                            }
                            Text("Detailed controls are always available in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        3 -> {
                            TourTile(Icons.Rounded.Backup, "Save your setup", "Create a backup from Settings whenever you need one.")
                            Button(onClick = viewModel::openBackupRestoreDialog, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Backup, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Text("Backup & Restore")
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                repeat(4) { index ->
                    Box(Modifier.size(if (index == page) 10.dp else 7.dp).background(if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) {
                    TextButton(onClick = {
                        page--
                        more = false
                    }) { Text("Back") }
                }
                Button(onClick = {
                    if (page == 3) {
                        onStartClick()
                    } else {
                        page++
                        more = false
                    }
                }, modifier = Modifier.weight(1f)) {
                    Text(
                        when (page) {
                            0 -> "Start fresh"
                            3 -> "Set up my AI"
                            else -> "Continue"
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    BackupRestoreHost(viewModel, onRestored)
}

@Composable
private fun TourTile(icon: ImageVector, title: String, description: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, modifier = Modifier.size(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
