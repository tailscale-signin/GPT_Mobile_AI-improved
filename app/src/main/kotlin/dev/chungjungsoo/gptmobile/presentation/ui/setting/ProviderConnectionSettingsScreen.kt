package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.presentation.common.FreeProviderPicker
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConnectionSettingsScreen(
    connectionUid: String,
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connections by settingViewModel.providerConnections.collectAsState()
    val profiles by settingViewModel.platformState.collectAsState()
    val connection = connections.firstOrNull { it.uid == connectionUid }

    if (connection == null) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Provider settings") },
                    navigationIcon = {
                        IconButton(onClick = onNavigationClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Text(
                "Provider connection is no longer available.",
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val isFree = connection.compatibleType == ClientType.FREE
    var name by remember(connection.uid, connection.name) { mutableStateOf(connection.name) }
    var apiUrl by remember(connection.uid, connection.apiUrl) { mutableStateOf(connection.apiUrl) }
    var keys by remember(connection.uid) { mutableStateOf(listOf("")) }
    var loaded by remember(connection.uid) { mutableStateOf(false) }
    var saving by remember(connection.uid) { mutableStateOf(false) }
    var status by remember(connection.uid) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(connection.uid) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connection.uid) {
        try {
            keys = if (isFree) emptyList() else settingViewModel.providerKeys(connection.uid).ifEmpty { listOf("") }
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "Could not load saved API keys. Reopen this page to try again."
        }
    }

    val childProfiles = profiles.filter { it.providerConnectionUid == connection.uid }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Provider Options") },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.SmartToy, contentDescription = null)
                            Text(connection.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            SettingsHelpIcon("Connection details are shared by every child AI profile. Model behavior stays inside each profile.")
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                        ) {
                            Text(
                                "${childProfiles.size} AI profile${if (childProfiles.size == 1) "" else "s"} linked",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Connection Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Provider Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (isFree) {
                            FreeProviderPicker(
                                apiUrl = apiUrl,
                                onProviderSelected = { provider ->
                                    if (name == FreeAiProvider.fromApiUrl(apiUrl)?.displayName) name = provider.displayName
                                    apiUrl = provider.apiUrl
                                },
                                enabled = !saving
                            )
                        } else {
                            OutlinedTextField(
                                value = apiUrl,
                                onValueChange = { apiUrl = it },
                                label = { Text("API HTTP Base URL") },
                                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("API Keys · Round Robin", style = MaterialTheme.typography.titleMedium)
                                SettingsHelpIcon("New requests rotate through the saved keys. Related tool rounds keep the same account.")
                            }
                            keys.forEachIndexed { index, key ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = key,
                                        onValueChange = { value -> keys = keys.toMutableList().apply { set(index, value) } },
                                        label = { Text("API key ${index + 1}") },
                                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                        modifier = Modifier.weight(1f),
                                        enabled = loaded && !saving,
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation()
                                    )
                                    IconButton(onClick = { keys = keys.filterIndexed { i, _ -> i != index }.ifEmpty { listOf("") } }, enabled = loaded && !saving) {
                                        Icon(Icons.Default.Delete, "Remove API key ${index + 1}")
                                    }
                                }
                            }
                            TextButton(onClick = { keys = keys + "" }, enabled = loaded && !saving) {
                                Icon(Icons.Default.Add, null)
                                Text(" API")
                            }
                        }
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            enabled = loaded &&
                                !saving &&
                                name.isNotBlank() &&
                                apiUrl.isNotBlank() &&
                                (!isFree || FreeAiProvider.fromApiUrl(apiUrl)?.isAvailable == true),
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        settingViewModel.saveProviderSettings(connection.copy(name = name.trim(), apiUrl = apiUrl.trim()), keys)
                                        status = "Provider connection saved"
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        status = "Could not save the connection. Your edits are still here; try again."
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Text(if (saving) " Saving…" else " Save Provider Connection")
                        }
                    }
                }
            }

            if (childProfiles.isNotEmpty()) {
                item {
                    Text("Child AI Profiles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(childProfiles, key = { it.uid }) { profile ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    profile.model.ifBlank { "Default model" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                if (profile.enabled) "Active" else "Disabled",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Delete Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Removes this provider, its saved credentials, and linked AI profiles.", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            }
        }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${connection.name}?") },
            text = { Text("This removes the provider, its saved credentials, and ${childProfiles.size} linked AI profile${if (childProfiles.size == 1) "" else "s"}. Conversation history is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    settingViewModel.deleteProviderConnection(connection)
                    onNavigationClick()
                }) { Text("Delete Provider", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    }
}


