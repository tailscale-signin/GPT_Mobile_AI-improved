package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.LocalSettingSearchFocus
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyDataScreen(settings: SettingViewModelV2, onBack: () -> Unit, initialFocus: String = "", openBackup: Boolean = false) {
    LaunchedEffect(openBackup) { if (openBackup) settings.openBackupRestoreDialog() }
    CompositionLocalProvider(LocalSettingSearchFocus provides initialFocus.substringAfter(':', initialFocus)) {
        Scaffold(topBar = { TopAppBar(title = { Text("Privacy & data") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { PrivacyStoragePanel(embedded = true) }
                item { AppDestinationRow("Backup & restore", "Choose sections, protect credentials and review a restore", Icons.Rounded.Backup, settings::openBackupRestoreDialog) }
            }
        }
        BackupRestoreHost(settings)
    }
}
