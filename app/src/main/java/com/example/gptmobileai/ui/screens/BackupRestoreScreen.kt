package com.example.gptmobileai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.backup.BackupManager
import com.example.gptmobileai.backup.BackupPreview
import com.example.gptmobileai.backup.BackupVersion
import com.example.gptmobileai.backup.BackupVersionManager
import com.example.gptmobileai.ui.components.LoadingIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    backupManager: BackupManager,
    versionManager: BackupVersionManager? = null,
    onBackupCreated: () -> Unit = {},
    onRestoreCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedBackupFile by remember { mutableStateOf<File?>(null) }
    var showRestorePreview by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("default-passphrase") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<BackupVersion>>(emptyList()) }
    
    // Loading states
    var isBackingUp by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf(0f) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        versionManager?.let {
            versions = it.listBackupVersions()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Backup & Restore") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Create Backup Section with Loading Indicator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Create New Backup", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            isBackingUp = true
                            backupProgress = 0f
                            coroutineScope.launch {
                                try {
                                    val backupDir = File(context.filesDir, "backups").also { it.mkdirs() }
                                    val target = File(backupDir, "backup_${System.currentTimeMillis()}.bin")
                                    val meta = backupManager.createEncryptedBackup(target, passphrase)
                                    
                                    // Simulate progress updates (replace with real progress from backupManager)
                                    for (i in 1..100 step 10) {
                                        backupProgress = i.toFloat()
                                        kotlinx.coroutines.delay(50)
                                    }
                                    
                                    selectedBackupFile = target
                                    isBackingUp = false
                                    backupProgress = 100f
                                    statusMessage = "Backup created successfully (${meta.sizeBytes} bytes)"
                                    versionManager?.let { versions = it.listBackupVersions() }
                                    onBackupCreated()
                                } catch (e: Exception) {
                                    isBackingUp = false
                                    backupProgress = 0f
                                    statusMessage = "Backup error: ${e.message}"
                                }
                            }
                        },
                        enabled = !isBackingUp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isBackingUp) {
                            LoadingIndicator(
                                isLoading = true,
                                progress = backupProgress,
                                message = "Creating encrypted backup...",
                                circular = false,
                                showProgress = true
                            )
                        } else {
                            Text("Backup All Data")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Restore Section with Loading Indicator
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Restore from Backup", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val backupDir = File(context.filesDir, "backups")
                            val latest = backupDir.listFiles()?.filter { it.extension == "bin" }?.maxByOrNull { it.lastModified() }
                            if (latest != null) {
                                selectedBackupFile = latest
                                statusMessage = "Selected latest: ${latest.name}"
                            } else {
                                statusMessage = "No backup files found."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedBackupFile != null) "Selected: ${selectedBackupFile!!.name}" else "Select Latest Backup File")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Restore All Button with Loading Indicator
                    if (selectedBackupFile != null) {
                        Button(
                            onClick = {
                                isRestoring = true
                                restoreProgress = 0f
                                showRestorePreview = false
                                coroutineScope.launch {
                                    try {
                                        val result = backupManager.restoreAllFromBackup(selectedBackupFile!!, passphrase)
                                        
                                        // Simulate progress updates (replace with real progress from backupManager)
                                        for (i in 1..100 step 5) {
                                            restoreProgress = i.toFloat()
                                            kotlinx.coroutines.delay(30)
                                        }
                                        
                                        if (result.success) {
                                            isRestoring = false
                                            restoreProgress = 100f
                                            statusMessage = "All data restored successfully!"
                                            onRestoreCompleted()
                                        } else {
                                            isRestoring = false
                                            restoreProgress = 0f
                                            statusMessage = "Restore failed."
                                        }
                                    } catch (e: Exception) {
                                        isRestoring = false
                                        restoreProgress = 0f
                                        statusMessage = "Restore error: ${e.message}"
                                    }
                                }
                            },
                            enabled = selectedBackupFile != null && !isRestoring,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isRestoring) {
                                LoadingIndicator(
                                    isLoading = true,
                                    progress = restoreProgress,
                                    message = "Restoring data...",
                                    circular = false,
                                    showProgress = true
                                )
                            } else {
                                Text("Restore All (Conversations + Secrets + Settings)")
                            }
                        }
                    }
                }
            }

            // Status Message with Loading Indicator
            if (statusMessage != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingPulse(isLoading = isBackingUp || isRestoring)
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusMessage!!.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Info text
            Text(
                "One button restores everything: conversations, API keys, and all settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Restore Preview Dialog with Loading Indicator
    if (showRestorePreview && selectedBackupFile != null) {
        RestorePreviewDialog(
            backupFile = selectedBackupFile!!,
            onConfirm = {
                isRestoring = true
                restoreProgress = 0f
                showRestorePreview = false
                coroutineScope.launch {
                    try {
                        val result = backupManager.restoreAllFromBackup(selectedBackupFile!!, passphrase)
                        
                        for (i in 1..100 step 5) {
                            restoreProgress = i.toFloat()
                            kotlinx.coroutines.delay(30)
                        }
                        
                        if (result.success) {
                            isRestoring = false
                            restoreProgress = 100f
                            statusMessage = "All data restored successfully!"
                            onRestoreCompleted()
                        } else {
                            isRestoring = false
                            restoreProgress = 0f
                            statusMessage = "Restore failed."
                        }
                    } catch (e: Exception) {
                        isRestoring = false
                        restoreProgress = 0f
                        statusMessage = "Restore error: ${e.message}"
                    }
                }
            },
            onCancel = { showRestorePreview = false }
        )
    }
}

@Composable
fun RestorePreviewDialog(
    backupFile: File,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val dateStr = remember(backupFile) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(backupFile.lastModified()))
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore Preview") },
        text = {
            Column {
                Text("This will restore ALL data from your backup:")
                Spacer(Modifier.height(8.dp))

                Text("📅 Backup timestamp: $dateStr")
                Text("🗨️ Conversations: Included")
                Text("🔐 Secrets: Included")
                Text("⚙️ Settings: Included")

                Spacer(Modifier.height(16.dp))

                Text(
                    "This will replace your current data with the backup's state. An atomic rollback copy is preserved.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Restore All")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
