package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.backup.BackupStatus
import dev.chungjungsoo.gptmobile.data.backup.GranularBackupOptions
import dev.chungjungsoo.gptmobile.data.localruntime.DiagnosticsTelemetryProvider
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeViewModel
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.util.getDynamicThemeTitle
import dev.chungjungsoo.gptmobile.util.getThemeModeTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onNavigateToAiPlatforms: () -> Unit,
    onNavigateToLocalModels: () -> Unit,
    onNavigateToToolConnections: () -> Unit,
    onNavigateToAboutPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms by settingViewModel.platformState.collectAsState()
    val dialogState by settingViewModel.dialogState.collectAsState()
    val localRuntimeBackend by settingViewModel.localRuntimeBackend.collectAsState()
    val debugMode by settingViewModel.debugMode.collectAsState()
    val backupStatus by settingViewModel.backupStatus.collectAsState()
    val context = LocalContext.current

    var selectedExportOptions by remember { mutableStateOf(GranularBackupOptions()) }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { settingViewModel.exportConfigurationToFile(it, options = selectedExportOptions) }
    }

    val restoreConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { settingViewModel.restoreConfigurationFromFile(it) }
    }

    val exportFavoritesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { settingViewModel.exportFavoritesToFile(it) }
    }

    val restoreFavoritesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { settingViewModel.restoreFavoritesFromFile(it) }
    }

    val exportDatabaseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { settingViewModel.exportDatabaseToFile(it) }
    }

    var pendingRestoreDatabaseUri by remember { mutableStateOf<Uri?>(null) }
    val confirmRestoreDatabaseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreDatabaseUri = uri
        }
    }

    LaunchedEffect(settingViewModel) {
        settingViewModel.uiEvent.collect { event ->
            when (event) {
                is SettingViewModelV2.UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Features & Integrations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // AI Platforms situated cleanly in a separate page menu entry ABOVE Local Models
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingNavigationRow(
                            icon = Icons.Default.SmartToy,
                            title = stringResource(R.string.ai_platforms),
                            subtitle = "${platforms.size} platform${if (platforms.size != 1) "s" else ""} (${platforms.count { it.enabled }} active)",
                            onClick = onNavigateToAiPlatforms
                        )
                        SettingNavigationRow(
                            icon = Icons.Default.Storage,
                            title = stringResource(R.string.local_models),
                            subtitle = stringResource(R.string.local_models_description),
                            onClick = onNavigateToLocalModels
                        )
                        SettingNavigationRow(
                            icon = Icons.Default.Build,
                            title = stringResource(R.string.tool_connections),
                            subtitle = stringResource(R.string.web_tools_description),
                            onClick = onNavigateToToolConnections
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Advanced Options",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Runtime Backend",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Select execution engine for on-device local models",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        LocalRuntimeBackend.entries.forEach { backend ->
                            val isSelected = backend == localRuntimeBackend
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { settingViewModel.updateLocalRuntimeBackend(backend) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { settingViewModel.updateLocalRuntimeBackend(backend) }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = backend.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when (backend) {
                                            LocalRuntimeBackend.QUALCOMM_QNN -> "Direct Hexagon NPU execution optimized for Snapdragon processors (lowest power & best performance)."
                                            LocalRuntimeBackend.LITERT_LM -> "Standard Google LiteRT-LM runtime using OpenCL and generic GPU compute shaders."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Debug Mode / Diagnostics Overlay Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingViewModel.updateDebugMode(!debugMode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Debug Mode & Diagnostics HUD",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Show real-time inference telemetry (active backend, tok/s rate, TTFT latency, hardware status) in chat",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = debugMode,
                                onCheckedChange = { settingViewModel.updateDebugMode(it) }
                            )
                        }

                        // Debug Mode Hardware Diagnostics & Developer Tools Panel
                        if (debugMode) {
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Hardware Diagnostics & Diagnostics HUD",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Active hardware telemetry sampled directly from system sensors & NPU drivers:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val diagSnapshot = remember {
                                DiagnosticsTelemetryProvider.getSnapshot(
                                    context = context,
                                    backendName = localRuntimeBackend.displayName,
                                    accelerator = if (localRuntimeBackend == LocalRuntimeBackend.QUALCOMM_QNN) "Qualcomm Hexagon HTP" else "LiteRT OpenCL GPU"
                                )
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("SoC Identifier:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = diagSnapshot.socModel,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("System RAM:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "${diagSnapshot.availableRamMb} MB free / ${diagSnapshot.totalRamGb} GB total",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Thermal State:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = diagSnapshot.thermalStatus,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Hexagon NPU Status:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = if (diagSnapshot.qnnReady) "HTP Skel Loaded (Ready)" else "CPU/GPU Fallback Mode",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (diagSnapshot.qnnReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val report = DiagnosticsTelemetryProvider.formatDiagnosticsText(diagSnapshot, null)
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Hardware Diagnostics", report))
                                        Toast.makeText(context, "Diagnostics report copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Copy Report", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val status = if (diagSnapshot.qnnReady) {
                                            "Qualcomm QNN HTP v73/v75/v79 libraries found in native library path."
                                        } else {
                                            "No Qualcomm HTP skeleton found. Ensure device is Snapdragon and native libs are bundled."
                                        }
                                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                    }
                                ) {
                                    Text("Probe NPU", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Data & App Management",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingNavigationRow(
                            icon = Icons.Default.Palette,
                            title = stringResource(R.string.theme_settings),
                            subtitle = stringResource(R.string.theme_description),
                            onClick = { settingViewModel.openThemeDialog() }
                        )

                        val backupSubtitle = if (backupStatus.lastBackupEpochMs != null) {
                            val formattedDate = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                                .format(Date(backupStatus.lastBackupEpochMs!!))
                            "Last backup: $formattedDate (${backupStatus.backupCount} created)"
                        } else {
                            stringResource(R.string.backup_and_restore_description)
                        }

                        SettingNavigationRow(
                            icon = Icons.Default.UploadFile,
                            title = stringResource(R.string.backup_and_restore),
                            subtitle = backupSubtitle,
                            onClick = { settingViewModel.openBackupRestoreDialog() }
                        )
                        SettingNavigationRow(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.about),
                            subtitle = stringResource(R.string.about_description),
                            onClick = onNavigateToAboutPage
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }

    if (dialogState.isThemeDialogOpen) {
        ThemeSettingDialog(settingViewModel)
    }

    if (dialogState.isBackupRestoreDialogOpen) {
        BackupRestoreOptionsDialog(
            backupStatus = backupStatus,
            onDismiss = settingViewModel::closeBackupRestoreDialog,
            onExportConfig = { options ->
                selectedExportOptions = options
                settingViewModel.closeBackupRestoreDialog()
                exportConfigLauncher.launch("gpt_mobile_config_${System.currentTimeMillis()}.enc")
            },
            onRestoreConfig = {
                settingViewModel.closeBackupRestoreDialog()
                restoreConfigLauncher.launch(arrayOf("*/*"))
            },
            onExportFavorites = {
                settingViewModel.closeBackupRestoreDialog()
                exportFavoritesLauncher.launch("gpt_mobile_favorites_${System.currentTimeMillis()}.enc")
            },
            onRestoreFavorites = {
                settingViewModel.closeBackupRestoreDialog()
                restoreFavoritesLauncher.launch(arrayOf("*/*"))
            },
            onExportDatabase = {
                settingViewModel.closeBackupRestoreDialog()
                exportDatabaseLauncher.launch("gpt_mobile_database_${System.currentTimeMillis()}.enc")
            },
            onRestoreDatabase = {
                settingViewModel.closeBackupRestoreDialog()
                confirmRestoreDatabaseLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    pendingRestoreDatabaseUri?.let { restoreUri ->
        AlertDialog(
            title = { Text(stringResource(R.string.restore_database_dialog_title)) },
            text = { Text(stringResource(R.string.restore_database_dialog_description)) },
            onDismissRequest = { pendingRestoreDatabaseUri = null },
            confirmButton = {
                Button(
                    onClick = {
                        settingViewModel.restoreDatabaseFromFile(restoreUri)
                        pendingRestoreDatabaseUri = null
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreDatabaseUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun BackupRestoreOptionsDialog(
    backupStatus: BackupStatus = BackupStatus(),
    onDismiss: () -> Unit,
    onExportConfig: (GranularBackupOptions) -> Unit,
    onRestoreConfig: () -> Unit,
    onExportFavorites: () -> Unit,
    onRestoreFavorites: () -> Unit,
    onExportDatabase: () -> Unit,
    onRestoreDatabase: () -> Unit
) {
    var includeFavorites by remember { mutableStateOf(true) }
    var includePlatforms by remember { mutableStateOf(true) }
    var includeTools by remember { mutableStateOf(true) }
    var includeUiPreferences by remember { mutableStateOf(true) }

    AlertDialog(
        title = {
            Text(stringResource(R.string.backup_and_restore))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Backup Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (backupStatus.lastBackupEpochMs != null) Icons.Default.CloudDone else Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Backup Status",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            val statusText = if (backupStatus.lastBackupEpochMs != null) {
                                val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                                    .format(Date(backupStatus.lastBackupEpochMs))
                                "Last: $dateStr (${backupStatus.backupCount} backups created)"
                            } else {
                                "No backups created yet"
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Granular Configuration Backup Section
                Text(
                    text = "Advanced Settings Backup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Export or restore encrypted platform configurations, MCP tools, UI preferences, and group settings (.enc).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Granular options toggles
                Text(
                    text = "Granular Options:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includePlatforms = !includePlatforms },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includePlatforms, onCheckedChange = { includePlatforms = it })
                    Text("AI Platforms & Models", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeTools = !includeTools },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeTools, onCheckedChange = { includeTools = it })
                    Text("MCP Tools & Web Search Connections", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeUiPreferences = !includeUiPreferences },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeUiPreferences, onCheckedChange = { includeUiPreferences = it })
                    Text("UI Preferences & Runtime Settings", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeFavorites = !includeFavorites },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeFavorites, onCheckedChange = { includeFavorites = it })
                    Text("Favorite Group Taxonomies", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val options = GranularBackupOptions(
                            includeFavorites = includeFavorites,
                            includePlatforms = includePlatforms,
                            includeTools = includeTools,
                            includeUiPreferences = includeUiPreferences
                        )
                        onExportConfig(options)
                    }
                ) {
                    Text(stringResource(R.string.export_configuration))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestoreConfig
                ) {
                    Text(stringResource(R.string.restore_configuration))
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Favorites Backup Section
                Text(
                    text = "Favorites Backup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Export or restore starred messages and custom groups (.enc).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onExportFavorites
                ) {
                    Text("Export Favorites (.enc)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestoreFavorites
                ) {
                    Text("Restore Favorites (.enc)")
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Full Database Backup Section
                Text(
                    text = stringResource(R.string.backup_database_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.backup_database_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onExportDatabase
                ) {
                    Text(stringResource(R.string.export_database))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestoreDatabase
                ) {
                    Text(stringResource(R.string.restore_database))
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun ThemeSettingDialog(
    settingViewModel: SettingViewModelV2
) {
    val themeViewModel = LocalThemeViewModel.current
    AlertDialog(
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(text = stringResource(R.string.dynamic_theme), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                DynamicTheme.entries.forEach { theme ->
                    RadioItem(
                        title = getDynamicThemeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalDynamicTheme.current == theme
                    ) {
                        themeViewModel.updateDynamicTheme(theme)
                    }
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
                Text(text = stringResource(R.string.dark_mode), style = MaterialTheme.typography.titleMedium)
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                )
                ThemeMode.entries.forEach { theme ->
                    RadioItem(
                        title = getThemeModeTitle(theme),
                        description = null,
                        value = theme.name,
                        selected = LocalThemeMode.current == theme
                    ) {
                        themeViewModel.updateThemeMode(theme)
                    }
                }
            }
        },
        onDismissRequest = settingViewModel::closeThemeDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::closeThemeDialog
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
private fun SettingNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
