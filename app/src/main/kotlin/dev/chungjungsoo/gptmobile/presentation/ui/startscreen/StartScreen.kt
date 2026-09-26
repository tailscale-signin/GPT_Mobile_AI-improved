package dev.chungjungsoo.gptmobile.presentation.ui.startscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.AdvancedOptions

@Composable
fun StartScreen(onStartClick: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Choose your AI", "Make it yours", "Start a conversation")
    val descriptions = listOf("Connect a provider, try Free, or use a model on your device.", "Use the wrench in a chat to choose models, tools and response style.", "Tap New chat and ask anything. Send another message while it works to queue your next thought.")
    val icons = listOf(Icons.Outlined.SmartToy, Icons.Outlined.Build, Icons.Outlined.AddComment)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onStartClick) { Text("Skip tour") } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icons[page], null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(104.dp).padding(16.dp))
                Text(titles[page], style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 20.dp))
                Text(descriptions[page], style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (page == 1) {
                    AdvancedOptions {
                        Text("Advanced Settings controls background work. Local models contains runtime tuning. Memory controls what is remembered. Debug and Statistics helps with testing.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("${page + 1} / 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
                Button(onClick = { if (page == 2) onStartClick() else page++ }, modifier = Modifier.weight(1f)) { Text(if (page == 2) "Set up my AI" else "Next") }
            }
        }
    }
}
