package dev.chungjungsoo.gptmobile.presentation.ui.startscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.ThemedAppIcon
import dev.chungjungsoo.gptmobile.presentation.ui.setting.BackupRestoreHost
import dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingViewModelV2

@Composable
fun StartScreen(onStartClick: () -> Unit, onLocalClick: () -> Unit = onStartClick, onRestored: () -> Unit = {}, viewModel: SettingViewModelV2 = hiltViewModel()) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ThemedAppIcon(Modifier.size(64.dp))
            Text("A clear space\nfor your AI.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
            Text("Bring your favourite provider, try a free model, or run a supported model on your device.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) { Text("Set up an AI profile") }
            OutlinedButton(onClick = onLocalClick, modifier = Modifier.fillMaxWidth()) { Text("Explore on-device models") }
            OutlinedButton(onClick = viewModel::openBackupRestoreDialog, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Restore, null, Modifier.padding(end = 8.dp))
                Text("Restore a backup")
            }
            Text("Local model setup shows download size, access requirements and device compatibility before installation.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showGuide = !showGuide }) { Text(if (showGuide) "Hide quick guide" else "How GPT Mobile works") }
            if (showGuide) {
                TourTile(Icons.Rounded.ChatBubble, "Conversations", "Ask, attach files and keep working while responses arrive.")
                TourTile(Icons.Rounded.SmartToy, "AI", "Manage profiles, provider connections, local models and delegation.")
                TourTile(Icons.Rounded.Tune, "Tools", "Choose plugins and control what each profile can access.")
                TourTile(Icons.Rounded.Memory, "Settings", "Personalise appearance, memory, privacy and data.")
            }
        }
    }
    BackupRestoreHost(viewModel, onRestored, restoreOnly = true)
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
