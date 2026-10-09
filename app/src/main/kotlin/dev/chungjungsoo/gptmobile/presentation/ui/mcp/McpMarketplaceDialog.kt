package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.catalog.MarketplacePresentation
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceSection
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceSort
import dev.chungjungsoo.gptmobile.data.catalog.McpCategory
import dev.chungjungsoo.gptmobile.data.catalog.McpPreset
import dev.chungjungsoo.gptmobile.data.catalog.McpPricingType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog as Dialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Public helpers are retained for existing connection/settings consumers.
data class ServiceBrand(
    val iconVector: ImageVector? = null,
    val iconResId: Int? = null,
    val brandColor: Color,
    val containerColor: Color
)

@Composable
fun getServiceBrand(iconName: String, category: McpCategory): ServiceBrand {
    val drawable = McpBrandAssets.drawableFor(iconName) ?: when (iconName) {
        "github" -> R.drawable.ic_github
        "brave" -> R.drawable.ic_brave
        else -> null
    }
    if (drawable != null) return ServiceBrand(iconResId = drawable, brandColor = MaterialTheme.colorScheme.primary, containerColor = MaterialTheme.colorScheme.primaryContainer)
    val icon = when {
        iconName in setOf("location", "refuge", "toronto", "arcgis", "tomtom", "openrouteservice") -> Icons.Rounded.TravelExplore
        iconName == "folder" -> Icons.Rounded.Folder
        iconName == "news" -> Icons.Rounded.Newspaper
        iconName == "airbnb" -> Icons.Rounded.Home
        category == McpCategory.SEARCH -> Icons.Rounded.Search
        category == McpCategory.DEVELOPMENT -> Icons.Rounded.Code
        category == McpCategory.DATABASE -> Icons.Rounded.Dns
        category == McpCategory.MEMORY -> Icons.Rounded.Psychology
        category == McpCategory.SYSTEM -> Icons.Rounded.Terminal
        else -> Icons.Rounded.Extension
    }
    return ServiceBrand(iconVector = icon, brandColor = MaterialTheme.colorScheme.primary, containerColor = MaterialTheme.colorScheme.primaryContainer)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpMarketplaceScreen(
    installedAliases: Set<String>,
    onNavigationClick: () -> Unit,
    onInstallPresetWithConfig: (
        preset: McpPreset,
        name: String,
        alias: String,
        endpointUrl: String,
        authType: String,
        credential: String,
        allowCleartext: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDelegation: (() -> Unit)? = null,
    marketplaceViewModel: MarketplaceViewModel = hiltViewModel(),
    connectionsViewModel: ToolConnectionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val packageState by marketplaceViewModel.uiState.collectAsStateWithLifecycle()
    val downloaded = packageState.downloadedIds
    val installations by marketplaceViewModel.installations.collectAsStateWithLifecycle()
    val connectionsState by connectionsViewModel.uiState.collectAsStateWithLifecycle()
    val features by connectionsViewModel.features.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(MarketplaceSection.PLUGINS) }
    var sort by rememberSaveable { mutableStateOf(MarketplaceSort.RECOMMENDED) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<McpCategory?>(null) }
    var pricing by rememberSaveable { mutableStateOf<McpPricingType?>(null) }
    var configuring by remember { mutableStateOf<McpPreset?>(null) }
    var editingConnection by remember { mutableStateOf<ToolConnection?>(null) }
    var approving by remember { mutableStateOf<GitHubMarketplacePackage?>(null) }
    var removing by remember { mutableStateOf<GitHubMarketplacePackage?>(null) }
    var removingConnection by remember { mutableStateOf<ToolConnection?>(null) }
    var pendingHostedSetup by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    editingConnection?.let { connection ->
        dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionEditorScreen(
            connectionUid = connection.connectionUid,
            viewModel = connectionsViewModel,
            onNavigationClick = { editingConnection = null },
            onSaveComplete = { editingConnection = null }
        )
        return
    }
    val pluginScroll = rememberLazyListState()
    val mcpScroll = rememberLazyListState()
    val presets = remember { GitHubMarketplaceCatalog.allPresets }
    fun added(preset: McpPreset) = preset.isPreinstalled ||
        preset.alias in installedAliases ||
        ToolConnectionsViewModel.normalizeAlias(preset.alias) in installedAliases
    val addedIds = presets.filter(::added).map { it.id }.toSet() + downloaded + installations.keys
    val filtered = remember(presets, section, query, category, pricing, sort, addedIds) {
        MarketplacePresentation.filterAndSort(presets, section, query, category, pricing, sort, addedIds)
    }
    LaunchedEffect(downloaded, packageState.errors) {
        pendingHostedSetup?.let { id ->
            if (id in downloaded) {
                configuring = GitHubMarketplaceCatalog.find(id)?.preset
                pendingHostedSetup = null
            } else if (id in packageState.errors) {
                pendingHostedSetup = null
            }
        }
    }
    LaunchedEffect(packageState.removingIds) {
        if (packageState.removingIds.isEmpty()) connectionsViewModel.refresh()
    }
    LaunchedEffect(Unit) {
        // Keep each tab's position, including restored state after rotation.
        snapshotFlow { listOf(query, category, pricing, sort) }.drop(1).collect {
            pluginScroll.scrollToItem(0)
            mcpScroll.scrollToItem(0)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val entry = exportId?.let(GitHubMarketplaceCatalog::find)
        exportId = null
        if (uri != null && entry != null) {
            scope.launch {
                try {
                    val bytes = marketplaceViewModel.exportBytes(entry)
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No export destination.")
                        output.use { it.write(bytes) }
                    }
                    marketplaceViewModel.showMessage("Package exported. Review its README for supported app setup.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    marketplaceViewModel.showMessage("Export failed. Retry with another destination or download the package again.")
                }
            }
        }
    }

    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        TopAppBar(title = { Text("Marketplace", fontWeight = FontWeight.Bold) }, navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.go_back))
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    ).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Extension, null, modifier = Modifier.size(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Your toolkit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Plugins & MCP tools",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search plugins, tools or capabilities") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }
                )
            }
            TabRow(
                selectedTabIndex = section.ordinal,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(16.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                MarketplaceSection.entries.forEach { tab ->
                    Tab(
                        selected = section == tab,
                        onClick = { section = tab },
                        text = { Text(tab.label, fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(if (tab == MarketplaceSection.PLUGINS) Icons.Rounded.Extension else Icons.Rounded.Dns, null) }
                    )
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketplaceDropdown("Sort: ${sort.label}", MarketplaceSort.entries.toList(), sort, { it.label }) { sort = it }
                MarketplaceDropdown(
                    category?.displayName ?: "All categories",
                    listOf<McpCategory?>(null) + McpCategory.entries,
                    category,
                    { it?.displayName ?: "All categories" }
                ) { category = it }
                MarketplaceDropdown(
                    pricing?.let(::pricingLabel) ?: "All pricing",
                    listOf<McpPricingType?>(null) + McpPricingType.entries,
                    pricing,
                    { it?.let(::pricingLabel) ?: "All pricing" }
                ) { pricing = it }
            }
            Text(
                "${filtered.size} results",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            packageState.message?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { marketplaceViewModel.showMessage(null) }) { Icon(Icons.Rounded.Close, "Dismiss message") }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = if (section == MarketplaceSection.PLUGINS) pluginScroll else mcpScroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(36.dp))
                                Text("No matching ${if (section == MarketplaceSection.PLUGINS) "plugins" else "tools"}", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    query = ""
                                    category = null
                                    pricing = null
                                }) { Text("Clear filters") }
                            }
                        }
                    }
                }
                items(filtered, key = { it.id }) { preset ->
                    val entry = GitHubMarketplaceCatalog.find(preset.id)
                    val native = entry?.let(NativeMarketplaceCatalog::supports) == true
                    val installation = installations[preset.id]
                    val connection = connectionsState.connections.firstOrNull { it.alias == preset.alias }
                    val builtinId = when (preset.id) {
                        "airbnb-native" -> ToolPluginId.AIRBNB
                        "builtin-memory" -> ToolPluginId.LOCAL_MEMORY
                        "builtin-model-delegation" -> ToolPluginId.MODEL_DELEGATION
                        "builtin-web" -> ToolPluginId.WEB_SEARCH
                        "device-location" -> ToolPluginId.DEVICE_LOCATION
                        else -> null
                    }
                    val enabled = if (native) installation?.enabled else (connection?.let { ToolPluginId.connection(it.connectionUid) } ?: builtinId)?.let { features.isToolPluginEnabled(it) }
                    MarketplacePackageCard(
                        preset, added(preset), onAddClick = {
                            when {
                                preset.integratedTool == "delegation" && onOpenDelegation != null -> onOpenDelegation()
                                preset.documentationOnly -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.websiteUrl))) }
                                connection != null -> editingConnection = connection
                                else -> configuring = preset
                            }
                        }, download = entry?.takeIf { it.runtime == MarketplaceRuntime.NATIVE },
                        downloaded = native && installation != null,
                        downloading = preset.id in packageState.downloadingIds, removing = preset.id in packageState.removingIds,
                        error = packageState.errors[preset.id], onDownload = { approving = entry }, onCancel = { marketplaceViewModel.cancelDownload(preset.id) },
                        onExport = {
                            exportId = preset.id
                            exporter.launch("${preset.id}.zip")
                        }, onRemove = { removing = entry },
                        enabled = enabled, canEnable = !native || installation?.ready(requireNotNull(entry)) == true,
                        onEnabledChange = { value ->
                            if (native && entry != null) {
                                marketplaceViewModel.setEnabled(entry, value)
                                if (value) connectionsViewModel.setPluginsEnabled(setOf(entry.id, ToolServiceCatalog.forPackage(entry).id), true)
                            } else {
                                (connection?.let { ToolPluginId.connection(it.connectionUid) } ?: builtinId)?.let { id ->
                                    connectionsViewModel.setPluginsEnabled(if (preset.id == "builtin-web") setOf(id, ToolPluginId.READ_URL) else setOf(id), value)
                                }
                            }
                        },
                        onRemoveConnection = connection?.let { { removingConnection = it } },
                        nativeSettings = if (native && entry != null && installation != null) {
                            { NativePluginSettings(entry, installation, marketplaceViewModel, preset.id in packageState.removingIds) }
                        } else {
                            null
                        },
                        needsSetup = native && installation != null && !installation.ready(requireNotNull(entry))
                    )
                }
            }
        }
    }
    approving?.let { entry ->
        AlertDialog(
            onDismissRequest = { approving = null },
            icon = { Icon(Icons.Rounded.Download, null) },
            title = { Text("Download and install ${entry.preset.name}?") },
            text = {
                Text(
                    "Download a pinned, checksum-checked package from ${GitHubMarketplaceCatalog.SOURCE_REPOSITORY}. " +
                        "GitHub receives this download request; no provider keys are sent. " +
                        (if (entry.runtime == MarketplaceRuntime.NATIVE) "Installs the Android adapter. Enable it after installation and any required setup. " else "Installs a hosted MCP connection package. Complete its connection setup. ") +
                        entry.serviceNotice
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    approving = null
                    if (entry.runtime == MarketplaceRuntime.HOSTED) pendingHostedSetup = entry.id
                    marketplaceViewModel.download(entry)
                }) { Text("Download & install") }
            },
            dismissButton = { TextButton(onClick = { approving = null }) { Text("Cancel") } }
        )
    }
    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Uninstall ${entry.preset.name}?") },
            text = { Text("Disables and removes the installed plugin, its package files, saved credentials and matching MCP connection and tool assignments.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    marketplaceViewModel.remove(entry)
                    connectionsViewModel.refresh()
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
    removingConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { removingConnection = null },
            title = { Text("Remove ${connection.name}?") },
            text = { Text("Remove the MCP connection, its credentials, saved grants and tool assignments.") },
            confirmButton = {
                TextButton(onClick = {
                    connectionsViewModel.deleteConnection(connection.connectionUid)
                    removingConnection = null
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { removingConnection = null }) { Text("Cancel") } }
        )
    }
    configuring?.let { preset ->
        val native = preset.integratedTool
        if (preset.id == "airbnb-native") {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.PluginConfigurationDialog(
                id = ToolPluginId.AIRBNB,
                name = preset.name,
                features = features,
                onFeature = connectionsViewModel::updateFeature,
                onSave = { connectionsViewModel.configurePlugin(ToolPluginId.AIRBNB, it) },
                onConnection = {
                    configuring = null
                    section = MarketplaceSection.MCP
                    query = "Airbnb"
                },
                onDismiss = { configuring = null }
            )
        } else if (native != null) {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalToolConfigurationDialog(native) { configuring = null }
        } else {
            McpPresetConfigureDialog(preset, onDismissRequest = { configuring = null }, onConfirm = { name, alias, endpoint, auth, key, cleartext ->
                onInstallPresetWithConfig(preset, name, alias, endpoint, auth, key, cleartext)
                configuring = null
            })
        }
    }
}

@Composable
private fun <T> MarketplaceDropdown(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = { if (option == selected) Icon(Icons.Rounded.Check, "Selected") }
                )
            }
        }
    }
}

private fun pricingLabel(pricing: McpPricingType): String = when (pricing) {
    McpPricingType.FREE -> "Free / self-hosted"
    McpPricingType.FREE_WITH_SIGNUP -> "Account / allowance"
    McpPricingType.PAID -> "Paid / trial"
}

@Composable
private fun MarketplaceBadge(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun PricingBadge(pricing: McpPricingType) = MarketplaceBadge(pricingLabel(pricing))

@Composable
fun PricingIcon(pricing: McpPricingType, modifier: Modifier = Modifier.size(16.dp), tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(
        when (pricing) {
            McpPricingType.FREE -> Icons.Rounded.Check
            McpPricingType.FREE_WITH_SIGNUP -> Icons.Rounded.Key
            McpPricingType.PAID -> Icons.Rounded.Star
        },
        null,
        modifier = modifier,
        tint = tint
    )
}

@Composable
fun ServiceIcon(iconName: String, category: McpCategory, modifier: Modifier = Modifier) {
    val brand = getServiceBrand(iconName, category)
    Box(modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(brand.containerColor), contentAlignment = Alignment.Center) {
        brand.iconResId?.let { Icon(painterResource(it), null, tint = brand.brandColor, modifier = Modifier.size(28.dp)) }
            ?: Icon(brand.iconVector ?: Icons.Rounded.Extension, null, tint = brand.brandColor, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun McpMarketplaceDetailCard(preset: McpPreset, isInstalled: Boolean, onAddClick: () -> Unit) {
    MarketplacePackageCard(preset, isInstalled, onAddClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketplacePackageCard(
    preset: McpPreset,
    isInstalled: Boolean,
    onAddClick: () -> Unit,
    download: GitHubMarketplacePackage? = null,
    downloaded: Boolean = false,
    downloading: Boolean = false,
    removing: Boolean = false,
    error: String? = null,
    onDownload: () -> Unit = {},
    onCancel: () -> Unit = {},
    onExport: () -> Unit = {},
    onRemove: () -> Unit = {},
    enabled: Boolean? = null,
    canEnable: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    onRemoveConnection: (() -> Unit)? = null,
    nativeSettings: (@Composable () -> Unit)? = null,
    needsSetup: Boolean = false
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(preset.id) { mutableStateOf(false) }
    ElevatedCard(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceIcon(preset.iconName, preset.category)
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PricingBadge(preset.pricing)
                MarketplaceBadge(
                    download?.runtime?.label ?: if (preset.isPreinstalled) {
                        "Included in app"
                    } else if (preset.documentationOnly) {
                        "Companion / guide"
                    } else {
                        "MCP connection"
                    }
                )
                if (downloaded || isInstalled) MarketplaceBadge(if (needsSetup) "Setup required" else "Ready")
            }
            Text(preset.description, style = MaterialTheme.typography.bodyMedium)
            if (downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading and checking integrity…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    download != null && !downloaded -> Button(onClick = onDownload, enabled = !downloading, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (error == null) "Download & install" else "Retry install")
                    }
                    download != null -> {
                        if (download.runtime != MarketplaceRuntime.NATIVE && download.canConnect && !isInstalled) Button(onClick = onAddClick, enabled = !removing, shape = RoundedCornerShape(12.dp)) { Text("Set up connection") }
                        TextButton(onClick = onRemove, enabled = !removing) { Text(if (removing) "Uninstalling…" else "Uninstall") }
                    }
                    preset.integratedTool != null || preset.isPreinstalled -> Button(onClick = onAddClick) { Text("Configure") }
                    !preset.documentationOnly -> Button(onClick = onAddClick) { Text(if (isInstalled) "Manage connection" else "Connect") }
                }
                enabled?.let { value ->
                    Button(onClick = { onEnabledChange(!value) }, enabled = !removing && (value || canEnable)) { Text(if (value) "Disable" else "Enable") }
                }
                if (download == null && onRemoveConnection != null) TextButton(onClick = onRemoveConnection) { Text("Uninstall") }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "Details")
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preset.websiteLink.isNotBlank()) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.websiteLink))) }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (preset.isPreinstalled) "Project website" else "Provider website")
                    }
                }
                if (download != null) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubMarketplaceCatalog.SOURCE_DIRECTORY))) }
                    }) { Text("GitHub source") }
                }
            }
            if (nativeSettings != null && (needsSetup || expanded)) nativeSettings()
            if (expanded) {
                HorizontalDivider()
                download?.let { Text(it.serviceNotice, style = MaterialTheme.typography.bodySmall) }
                preset.toolCapabilities.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (preset.setupInstructions.isNotBlank()) Text(preset.setupInstructions, style = MaterialTheme.typography.bodySmall)
                if (download != null) {
                    OutlinedButton(onClick = onExport, enabled = downloaded && !removing) { Text("Export package") }
                }
            }
        }
    }
}

@Composable
fun McpPresetConfigureDialog(
    preset: McpPreset,
    onDismissRequest: () -> Unit,
    onConfirm: (name: String, alias: String, endpoint: String, authType: String, credential: String, allowCleartext: Boolean) -> Unit
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var alias by remember(preset.id) { mutableStateOf(preset.alias) }
    var endpoint by remember(preset.id) { mutableStateOf(preset.defaultEndpoint) }
    var auth by remember(preset.id) { mutableStateOf(preset.suggestedAuthType) }
    // Never put credentials or secret-bearing endpoint values in rememberSaveable.
    var credential by remember(preset.id) { mutableStateOf("") }
    var endpointKey by remember(preset.id) { mutableStateOf("") }
    var cleartext by remember(preset.id) { mutableStateOf(false) }
    val normalized = ToolConnectionsViewModel.normalizeAlias(alias)
    val validAlias = ToolConnectionsViewModel.isValidAlias(normalized)
    val actualEndpoint = if (preset.requiredEndpointQueryParameter != null && endpointKey.isNotBlank()) {
        runCatching { endpoint.trim().toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(preset.requiredEndpointQueryParameter, endpointKey.trim())?.build()?.toString().orEmpty() }.getOrDefault("")
    } else {
        endpoint.trim()
    }
    val validEndpoint = ToolConnectionsViewModel.isValidMcpEndpoint(actualEndpoint, cleartext) && preset.hasRequiredEndpointParameters(actualEndpoint)
    val needsKey = auth in setOf(ToolConnectionAuthType.BEARER, ToolConnectionAuthType.API_KEY)
    val validKey = !needsKey || NativeMarketplaceCatalog.validKey(credential.trim())
    val requiredColors = OutlinedTextFieldDefaults.colors(errorBorderColor = MaterialTheme.colorScheme.error, errorLabelColor = MaterialTheme.colorScheme.error, errorSupportingTextColor = MaterialTheme.colorScheme.error, errorCursorColor = MaterialTheme.colorScheme.error)
    val canSave = name.isNotBlank() && validAlias && validEndpoint && validKey
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).padding(8.dp), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Set up ${preset.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismissRequest) { Icon(Icons.Rounded.Close, "Close") }
                }
                if (preset.setupInstructions.isNotBlank()) Text(preset.setupInstructions, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = name.isBlank())
                OutlinedTextField(
                    alias,
                    { alias = it },
                    label = { Text("Tool alias") },
                    readOnly = GitHubMarketplaceCatalog.find(preset.id) != null || dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog.findById(preset.id) != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !validAlias,
                    supportingText = { Text("Lowercase letters, numbers and underscores; start with a letter.") }
                )
                OutlinedTextField(
                    endpoint,
                    { endpoint = it },
                    label = { Text("MCP Streamable HTTP URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !ToolConnectionsViewModel.isValidMcpEndpoint(endpoint.trim(), cleartext),
                    visualTransformation = if (preset.requiredEndpointQueryParameter != null) PasswordVisualTransformation() else VisualTransformation.None,
                    supportingText = { Text("Enter an MCP URL, not a provider REST or model inference URL.") },
                    colors = requiredColors
                )
                preset.requiredEndpointQueryParameter?.let { parameter ->
                    OutlinedTextField(
                        endpointKey, { endpointKey = it }, label = { Text("$parameter · API key required") },
                        supportingText = { Text("Required before use. Stored through the endpoint-secret vault.") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = !preset.hasRequiredEndpointParameters(actualEndpoint), colors = requiredColors
                    )
                }
                if (endpoint.startsWith("http://", ignoreCase = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cleartext, { cleartext = it })
                        Text("Allow cleartext HTTP on a trusted private network", style = MaterialTheme.typography.bodySmall)
                    }
                }
                MarketplaceDropdown(
                    "Authentication: " + when (auth) {
                        ToolConnectionAuthType.OAUTH -> "Browser sign-in"
                        ToolConnectionAuthType.BEARER -> "Bearer token"
                        ToolConnectionAuthType.API_KEY -> "API key"
                        else -> "None / endpoint key"
                    },
                    when {
                        preset.suggestedAuthType in setOf(ToolConnectionAuthType.BEARER, ToolConnectionAuthType.API_KEY) && preset.requiredFields.isNotEmpty() -> listOf(preset.suggestedAuthType)
                        preset.requiredEndpointQueryParameter != null -> listOf(ToolConnectionAuthType.NONE)
                        else -> listOf(ToolConnectionAuthType.NONE, ToolConnectionAuthType.BEARER, ToolConnectionAuthType.OAUTH)
                    },
                    auth,
                    {
                        when (it) {
                            ToolConnectionAuthType.OAUTH -> "Browser sign-in (OAuth)"
                            ToolConnectionAuthType.BEARER -> "Bearer / API token"
                            ToolConnectionAuthType.API_KEY -> "API key"
                            else -> "None / endpoint key"
                        }
                    }
                ) { auth = it }
                if (needsKey) {
                    OutlinedTextField(
                        credential,
                        { credential = it },
                        label = { Text(preset.requiredFields.firstOrNull() ?: "Bearer token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !validKey,
                        colors = requiredColors,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
                if (auth == ToolConnectionAuthType.OAUTH) Text("Save, then Authorize in connection settings. Provider client approval may be required.", style = MaterialTheme.typography.bodySmall)
                Text("Saving a connection does not prove it is online. Discover its tools and grant only the access you need.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismissRequest) { Text("Cancel") }
                    Button(onClick = { onConfirm(name.trim(), normalized, actualEndpoint, auth, if (needsKey) credential.trim() else "", cleartext) }, enabled = canSave) {
                        Text("Add to connections")
                    }
                }
            }
        }
    }
}
