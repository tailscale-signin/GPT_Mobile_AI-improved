package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog
import dev.chungjungsoo.gptmobile.data.free.FreeServiceStatus
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeModelsSettingsScreen(viewModel: SettingViewModelV2, onBack: () -> Unit, onOpenProfile: (String) -> Unit, modifier: Modifier = Modifier) {
    val profiles by viewModel.platformState.collectAsState()
    val states by FreeCatalogState.status.collectAsState()
    val catalog by FreeCatalogState.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { FreeAiProvider.entries.forEach { provider -> launch { FreeCatalogState.refresh(provider) } } }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text(FreeModelCatalog.NAME) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }, actions = {
            IconButton(enabled = states.values.none { it.checking }, onClick = { FreeAiProvider.entries.forEach { provider -> scope.launch { FreeCatalogState.refresh(provider, true) } } }) { Icon(Icons.Rounded.Refresh, "Refresh service status and models") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)), RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp))
                    Text("Ready to explore", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${FreeAiProvider.entries.size} services · ${FreeAiProvider.entries.sumOf { FreeCatalogState.models(it).size }} model options", style = MaterialTheme.typography.titleMedium)
                    Text("Profiles are included from the start. Choose a service and model; no paid route is selected automatically.", style = MaterialTheme.typography.bodyMedium)
                    Text("Public services: automatic memory and personal attachment sharing stay off. Limits can be shared with other users on your IP.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(FreeAiProvider.entries, key = { it.name }) { provider ->
                // Read catalog state to recompose model options after a refresh.
                val models = remember(provider, catalog) { FreeCatalogState.models(provider) }
                val children = profiles.filter { it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE && FreeAiProvider.fromApiUrl(it.apiUrl) == provider }
                FreeServiceCard(provider, states[provider] ?: FreeServiceStatus(), children, models, onOpenProfile, viewModel::updatePlatform)
            }
            item { Text("Your own hardware", style = MaterialTheme.typography.titleLarge) }
            item { Text("Local servers keep their local transport, memory and privacy settings. On a phone, localhost means the phone—not your PC.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(listOf("llama.cpp / llama-server" to 8080, "Ollama" to 11434, "LM Studio" to 1234, "KoboldCpp" to 5001, "LocalAI" to 8080), key = { it.first }) { (name, port) ->
                LocalFreeServerCard(name, port, viewModel)
            }
            item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("WebLLM", style = MaterialTheme.typography.titleMedium)
                        Text("Browser WebGPU runtime · native Android integration is not available. It cannot be used as an HTTP profile.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FreeServiceCard(provider: FreeAiProvider, status: FreeServiceStatus, profiles: List<PlatformV2>, models: List<dev.chungjungsoo.gptmobile.data.free.FreeModelOption>, onOpenProfile: (String) -> Unit, update: (PlatformV2) -> Unit) {
    var expanded by remember(provider) { mutableStateOf(false) }
    val details = remember(provider) { FreeModelCatalog.details(provider) }
    val uri = LocalUriHandler.current
    val stale = status.checkedAt == 0L || System.currentTimeMillis() - status.checkedAt > 600_000
    val label = when {
        status.checking -> "Checking…"
        stale -> "Not checked recently"
        status.reachable == true -> "Reachable"
        status.reachable == false -> "Unavailable"
        else -> "Not checked"
    }
    val color = when {
        status.checking || stale -> MaterialTheme.colorScheme.outline
        status.reachable == true -> Color(0xFF218A53)
        status.reachable == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Text(provider.displayName.take(2).uppercase(), Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f)) {
                    Text(provider.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${details.lifecycle} · ${models.size} ${if (models.size == 1) "model" else "models"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(12.dp).background(color, CircleShape).semantics { contentDescription = "$label: ${provider.displayName}" })
            }
            Text("$label · ${status.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.checkedAt > 0) Text("Checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(status.checkedAt))}", style = MaterialTheme.typography.labelSmall)
            Text(details.authentication, style = MaterialTheme.typography.labelLarge)
            Text(details.limits, style = MaterialTheme.typography.bodyMedium)
            profiles.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall)
                        if (dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.model(provider, profile.model)?.tools != true) Text("Native tools unavailable · app-assisted search when authorized", style = MaterialTheme.typography.labelSmall)
                        Text(profile.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(checked = profile.enabled, onCheckedChange = { update(profile.copy(enabled = it)) }, modifier = Modifier.semantics { contentDescription = "Enable ${profile.name}" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onOpenProfile(profile.uid) }) { Text("Profile options") }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Models & details") }
                }
            }
            if (profiles.isEmpty()) Text("Profile removed · add this service from AI profiles to use it again.", style = MaterialTheme.typography.bodySmall)
            if (expanded || profiles.isEmpty()) {
                Text(details.transport, style = MaterialTheme.typography.titleSmall)
                Text(details.privacy, style = MaterialTheme.typography.bodySmall)
                Text(details.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Structured output: ${details.structuredOutput}", style = MaterialTheme.typography.bodySmall)
                status.servedModel?.let { Text("Last served model: $it", style = MaterialTheme.typography.labelMedium) }
                status.remaining?.let { Text("Server-reported allowance: $it", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { uri.openUri(details.documentation) }) { Text("Provider documentation & policies") }
                models.forEach { model ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.label, style = MaterialTheme.typography.titleSmall)
                            Text("Client tools: ${if (model.tools) "advertised" else "off / unverified"} · Streaming: ${if (model.streaming) "supported by transport" else "queued or buffered"}", style = MaterialTheme.typography.bodySmall)
                            model.privacy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            profiles.firstOrNull()?.let { profile -> TextButton(onClick = { update(profile.copy(model = model.id)) }, enabled = profile.model != model.id) { Text(if (profile.model == model.id) "Selected" else "Use this model") } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalFreeServerCard(name: String, port: Int, viewModel: SettingViewModelV2) {
    var configure by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var anonymous by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("Private server · optional key · no hosted API charge · requires your server address", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { configure = !configure }) { Text(if (configure) "Close" else "Connect your server") }
            if (configure) {
                androidx.compose.material3.OutlinedTextField(host, { host = it }, label = { Text("Server URL, e.g. http://192.168.1.20:$port/v1") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(model, { model = it }, label = { Text("Model ID on your server") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Anonymous / no key", Modifier.weight(1f))
                    Switch(anonymous, { anonymous = it })
                }
                if (!anonymous) androidx.compose.material3.OutlinedTextField(key, { key = it }, label = { Text("Server API key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(enabled = !checking && host.isNotBlank() && model.isNotBlank() && (anonymous || key.isNotBlank()), onClick = {
                    checking = true
                    scope.launch {
                        try {
                            val type = if (name == "Ollama") dev.chungjungsoo.gptmobile.data.model.ClientType.OLLAMA else dev.chungjungsoo.gptmobile.data.model.ClientType.CUSTOM
                            val endpoint = dev.chungjungsoo.gptmobile.data.network.LocalServerUrls.openAiBase(host)
                            when (val result = dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.validate(type, endpoint, if (anonymous) "" else key)) {
                                is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Success -> {
                                    viewModel.addPlatform(PlatformV2(name = name, compatibleType = type, apiUrl = endpoint, model = model.trim(), token = if (anonymous) "" else key, enabled = true))
                                    error = "Connected. Your local AI profile is available."
                                    configure = false
                                }
                                is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Error -> error = result.message
                                else -> Unit
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message
                        } finally {
                            checking = false
                        }
                    }
                }) { Text(if (checking) "Checking server…" else "Test & add local profile") }
            }
        }
    }
}
