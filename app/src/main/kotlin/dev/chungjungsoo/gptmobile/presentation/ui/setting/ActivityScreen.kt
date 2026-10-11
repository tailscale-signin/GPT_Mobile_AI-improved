package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.ui.workspace.WorkspaceScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityScreen(
    settings: SettingViewModelV2,
    tools: ToolConnectionsViewModel,
    initialTab: String,
    onBack: () -> Unit,
    onChat: (Int) -> Unit,
    onCollectionPreferences: () -> Unit = {}
) {
    val tabs = listOf("Runs", "Usage", "Benchmarks", "Diagnostics")
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(tabs.indexOf(initialTab).coerceAtLeast(0)) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Activity") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp)) { SettingsTabs(tabs, tab) { tab = it } }
            when (tab) {
                0 -> WorkspaceScreen(onBack, onChat, initialTab = "Tasks", focused = true, embedded = true)
                1 -> UsageStatisticsScreen(onBack = onBack, embedded = true)
                2 -> ProfileBenchmarkScreen(onBack = onBack, onUsage = { tab = 1 }, embedded = true)
                3 -> {
                    androidx.compose.material3.TextButton(onClick = onCollectionPreferences, modifier = Modifier.padding(horizontal = 20.dp)) { Text("Diagnostic collection preferences") }
                    DebugDiagnosticsScreen(settings, onBack, { tab = 1 }, { tab = 2 }, toolConnectionsViewModel = tools, embedded = true)
                }
            }
        }
    }
}
