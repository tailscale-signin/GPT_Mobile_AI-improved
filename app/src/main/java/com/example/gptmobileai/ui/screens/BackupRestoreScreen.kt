package com.example.gptmobileai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.gptmobileai.backup.BackupManager
import com.example.gptmobileai.backup.BackupPreview
import com.example.gptmobileai.backup.BackupVersion
import com.example.gptmobileai.backup.BackupVersionManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    backupManager: BackupManager,
    versionManager: BackupVersionManager? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedBackupFile by remember { mutableStateOf<File?>(null) }
    var showRestorePreview by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<BackupVersion>>(emptyList()) }

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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Create Backup Section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Create New Backup", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backup passphrase") },
                        supportingText = { Text("Use at least 8 characters. Keep it safe; it cannot be recovered.") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        enabled = passphrase.length >= 8,
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val backupDir = File(context.filesDir, "backups").also { it.mkdirs() }
                                    val target = File(backupDir, "backup_${System.currentTimeMillis()}.bin")
                                    val meta = backupManager.createEncryptedBackup(target, passphrase)
                                    selectedBackupFile = target
                                    statusMessage = "Backup created successfully (${meta.sizeBytes} bytes)"
                                    versionManager?.let { versions = it.listBackupVersions() }
                                } catch (e: Exception) {
                                    statusMessage = "Backup error: ${e.message}"
                                }
                            }
                        }
                    ) {
                        Text("Backup All Data")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Restore Section - SINGLE BUTTON (Consolidated)
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
                        }
                    ) {
                        Text(selectedBackupFile?.let { "Selected: ${it.name}" } ?: "Select Latest Backup File")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Restore All Button - THE MAIN FEATURE
            if (selectedBackupFile != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Restore All Data", style = MaterialTheme.typography.titleMedium)

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { showRestorePreview = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Restore All (Conversations + Secrets + Settings)")
                        }
                    }
                }
            }

            if (statusMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
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

    // Restore Preview Dialog
    selectedBackupFile?.takeIf { showRestorePreview }?.let { backupFile ->
        RestorePreviewDialog(
            backupFile = backupFile,
            onConfirm = {
                coroutineScope.launch {
                    try {
                        val result = backupManager.restoreAllFromBackup(backupFile, passphrase)
                        if (result.success) {
                            statusMessage = "All data restored successfully!"
                        } else {
                            statusMessage = "Restore failed."
                        }
                    } catch (e: Exception) {
                        statusMessage = "Restore error: ${e.message}"
                    } finally {
                        showRestorePreview = false
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
