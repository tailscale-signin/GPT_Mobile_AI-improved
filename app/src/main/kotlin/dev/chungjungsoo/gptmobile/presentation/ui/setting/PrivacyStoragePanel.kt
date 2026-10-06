package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PrivacyStoragePanel(embedded: Boolean = false) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) }
    var locked by remember { mutableStateOf(preferences.getBoolean("app_lock", false)) }
    var secure by remember { mutableStateOf(preferences.getBoolean("secure_screen", false)) }
    var sizes by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        sizes = withContext(Dispatchers.IO) {
            listOf("App files" to context.filesDir, "Local memory & protected data" to context.noBackupFilesDir, "Cache" to context.cacheDir).map { (name, folder) -> name to folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val panelContent: @Composable () -> Unit = {
        Column(Modifier.padding(if (embedded) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!embedded) Text("Privacy & Storage", style = MaterialTheme.typography.titleLarge)
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Require Device Unlock")
                    Text("Locks after 30 seconds away", style = MaterialTheme.typography.bodySmall)
                }
                Switch(locked, { enabled ->
                    if (enabled && !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
                        message = "Set a device PIN or password first."
                    } else {
                        locked = enabled
                        preferences.edit().putBoolean("app_lock", enabled).apply()
                    }
                })
            }
            Row {
                Text("Hide Screenshots And Recent-App Previews", Modifier.weight(1f))
                Switch(secure, { enabled ->
                    secure = enabled
                    preferences.edit().putBoolean("secure_screen", enabled).apply()
                    (context as? Activity)?.window?.let { window -> if (enabled || locked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                })
            }
            sizes.forEach { (name, size) -> Text("$name · ${"%.1f".format(size / 1_048_576.0)} MB", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { confirmCleanup = true }) { Text("Clear Attachment Previews") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (embedded) {
        panelContent()
    } else {
        Card { panelContent() }
    }
    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Clear previews?") },
            text = { Text("Removes temporary copies used by external viewers. Original attachments and downloaded models are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { File(context.cacheDir, "attachment-previews").deleteRecursively() }
                        message = if (removed) "Preview copies cleared." else "Some previews could not be removed."
                        refresh()
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } }
        )
    }
}
