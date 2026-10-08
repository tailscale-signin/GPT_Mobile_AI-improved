package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.presentation.common.DestinationCard
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.util.PERMISSION_ACCESS_LOCAL_NETWORK
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior
import dev.chungjungsoo.gptmobile.util.requiresLocalNetworkAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolConnectionsScreen(
    modifier: Modifier = Modifier,
    viewModel: ToolConnectionsViewModel = hiltViewModel(),
    onLaunchOAuth: (String) -> Unit = {},
    onMarketplaceClick: () -> Unit = {},
    onAddConnectionClick: () -> Unit,
    onEditConnectionClick: (String) -> Unit,
    onNavigationClick: () -> Unit
) {
    val features by viewModel.features.collectAsStateWithLifecycle()
    var search by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var memorySettingsOpen by remember { mutableStateOf(false) }
    if (memorySettingsOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { memorySettingsOpen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            FactVaultScreen(hiltViewModel(), onBack = { memorySettingsOpen = false })
        }
    }
    var toolkitFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ToolkitFilter.ALL) }
    var toolkitSort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ToolkitSort.NAME) }
    val marketplaceViewModel: dev.chungjungsoo.gptmobile.presentation.ui.mcp.MarketplaceViewModel = hiltViewModel()
    val installations by marketplaceViewModel.installations.collectAsStateWithLifecycle()
    val marketplaceState by marketplaceViewModel.uiState.collectAsStateWithLifecycle()
    var pluginSettings by remember { mutableStateOf<IntegratedPluginUi?>(null) }
    var amazonMcpSetup by remember { mutableStateOf(false) }
    if (amazonMcpSetup) {
        dev.chungjungsoo.gptmobile.presentation.ui.mcp.McpPresetConfigureDialog(
            preset = requireNotNull(dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog.findById("jannafta-amazon")),
            onDismissRequest = { amazonMcpSetup = false },
            onConfirm = { name, alias, endpoint, auth, credential, cleartext ->
                viewModel.saveConnection(
                    existing = null,
                    provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                    name = name,
                    alias = alias,
                    endpointUrl = endpoint,
                    authType = auth,
                    credential = credential,
                    oauthClientId = "",
                    allowCleartext = cleartext,
                    clearCredential = false,
                    onSuccess = { amazonMcpSetup = false }
                )
            }
        )
    }
    var delegationSettingsOpen by remember { mutableStateOf(false) }
    var pairingLink by remember { mutableStateOf<String?>(null) }
    val pairingContext = LocalContext.current
    pairingLink?.let { entered ->
        val valid = runCatching { dev.chungjungsoo.gptmobile.data.pairing.PairingLink.parse(entered) }.isSuccess
        AlertDialog(
            onDismissRequest = { pairingLink = null },
            title = { Text(stringResource(R.string.pair_server_title)) },
            text = { OutlinedTextField(entered, { pairingLink = it.take(4096) }, label = { Text(stringResource(R.string.pair_server_link)) }) },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    pairingContext.startActivity(Intent(pairingContext, ServerPairingActivity::class.java).setData(Uri.parse(entered)))
                    pairingLink = null
                }) { Text(stringResource(R.string.pair_server_review)) }
            },
            dismissButton = { TextButton(onClick = { pairingLink = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var browsingConnection by remember { mutableStateOf<ToolConnection?>(null) }
    browsingConnection?.let { connection ->
        if (connection.type == ToolConnectionType.GITHUB) {
            GitHubWorkspaceScreen(connection, onDismiss = { browsingConnection = null })
        } else {
            McpBrowserDialog(connection, onDismiss = { browsingConnection = null })
        }
    }
    var permissionsConnection by remember { mutableStateOf<ToolConnection?>(null) }
    var deletingConnection by remember { mutableStateOf<ToolConnection?>(null) }
    var pendingOAuthConnection by remember { mutableStateOf<ToolConnection?>(null) }
    permissionsConnection?.let { selected ->
        ToolPolicyDialog(selected, onDismiss = { permissionsConnection = null }, onResetGrants = { viewModel.revokeToolGrants(selected.connectionUid) }) { policy, reads ->
            viewModel.savePolicy(selected, policy, reads)
            permissionsConnection = null
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingOAuthConnection
        pendingOAuthConnection = null
        if (granted && pending != null) {
            viewModel.startOAuth(pending.connectionUid)
        } else if (!granted) {
            Toast.makeText(context, R.string.local_network_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.oauthLaunches.collect(onLaunchOAuth)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val services = toolServiceItems(uiState.connections, installations)
    fun serviceEnabled(service: ToolServiceItem): Boolean = features.isToolPluginEnabled(service.id) &&
        (service.integrated || service.packages.any { installations[it.id]?.enabled == true } || service.connections.any { features.isToolPluginEnabled(ToolPluginId.connection(it.connectionUid)) })
    fun connectionReady(connection: ToolConnection): Boolean = !connection.endpointUrl.isNullOrBlank() &&
        (connection.authType == ToolConnectionAuthType.NONE || connection.secretRef != null)
    fun startOAuth(connection: ToolConnection) {
        val needsPermission = connection.endpointUrl?.let(::requiresLocalNetworkAccess) == true
        if (needsPermission &&
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingOAuthConnection = connection
            localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
        } else {
            viewModel.startOAuth(connection.connectionUid)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ToolConnectionsTopBar(
                scrollBehavior = scrollBehavior,
                onAddClick = onAddConnectionClick,
                onMarketplaceClick = onMarketplaceClick,
                onNavigationClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsHero("YOUR SERVICES", "Plugins & Tools", "One service. All its tools. Choose what each AI can use.")
            ToolkitControls(search, { search = it }, toolkitFilter, { toolkitFilter = it }, toolkitSort, { toolkitSort = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMarketplaceClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Storefront, null, Modifier.size(18.dp))
                    Text("Marketplace", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onAddConnectionClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Text("Connect", Modifier.padding(start = 6.dp))
                }
            }
            Text("Plugins run in this app. MCP tools run on a connected remote server. Profile switches are available in AI → Profile → Tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (toolkitFilter != ToolkitFilter.PLUGINS) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remote MCP", style = MaterialTheme.typography.titleSmall)
                            Text("Allow connected server tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = features.remoteMcpConnections, onCheckedChange = viewModel::setRemoteMcpEnabled)
                    }
                }
                TextButton(onClick = { pairingLink = "" }) { Text(stringResource(R.string.pair_server_title)) }
            }
            val visible = sortedToolServices(services, search, toolkitFilter, toolkitSort, ::serviceEnabled) { it.requiredFields(installations).isNotEmpty() }
            if (visible.isEmpty()) Text("No services match your selection.", style = MaterialTheme.typography.bodyMedium)
            visible.forEach { service ->
                key(service.id) {
                    val required = service.requiredFields(installations)
                    val ready = (service.integrated && service.id != ToolPluginId.AMAZON_SEARCH) ||
                        service.connections.any(::connectionReady) ||
                        service.packages.any { installations[it.id]?.ready(it) == true }
                    ToolServiceCard(
                        service,
                        checked = serviceEnabled(service),
                        onCheckedChange = { enabled ->
                            viewModel.setPluginsEnabled(setOf(service.id) + service.connections.map { ToolPluginId.connection(it.connectionUid) }, enabled)
                            service.packages.forEach { entry ->
                                if (!enabled || installations[entry.id]?.ready(entry) == true) marketplaceViewModel.setEnabled(entry, enabled)
                            }
                        },
                        status = listOfNotNull("In-app plugin".takeIf { service.hasPlugin }, "Remote MCP".takeIf { service.hasMcp }).joinToString(" · "),
                        required = required,
                        toggleEnabled = ready || serviceEnabled(service)
                    ) {
                        if (service.integrated) {
                            val plugin = INTEGRATED_PLUGINS.first { it.id == service.id }
                            OutlinedButton(onClick = {
                                when (service.id) {
                                    ToolPluginId.MODEL_DELEGATION -> delegationSettingsOpen = true
                                    ToolPluginId.LOCAL_MEMORY -> memorySettingsOpen = true
                                    else -> pluginSettings = plugin
                                }
                            }) {
                                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                                Text("Configure", Modifier.padding(start = 8.dp))
                            }
                            if (service.id == ToolPluginId.AMAZON_SEARCH && service.connections.isEmpty()) {
                                TextButton(onClick = onAddConnectionClick) { Text("Connect Amazon Search · SerpApi") }
                            }
                            if (service.id == ToolPluginId.AMAZON_SEARCH) {
                                OutlinedButton(onClick = { amazonMcpSetup = true }) {
                                    Icon(Icons.Rounded.Hub, null, Modifier.size(18.dp))
                                    Text("Connect Jan Nafta MCP", Modifier.padding(start = 8.dp))
                                }
                            }
                            if (service.id == ToolPluginId.AMAZON_FREE) {
                                Text("No API key required · Canada, US, UK and France. Tap a product for details and automatically loaded price history. Amazon may block public pages.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = viewModel::testAmazonFreeSearch,
                                    enabled = features.isToolPluginEnabled(ToolPluginId.AMAZON_FREE) && uiState.connectionHealth[ToolPluginId.AMAZON_FREE]?.status != ToolConnectionHealthStatus.CHECKING
                                ) { Text("Test search · 1 request") }
                                uiState.connectionHealth[ToolPluginId.AMAZON_FREE]?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        service.packages.forEach { entry ->
                            installations[entry.id]?.let { installation ->
                                dev.chungjungsoo.gptmobile.presentation.ui.mcp.NativePluginSettings(entry, installation, marketplaceViewModel, busy = entry.id in marketplaceState.removingIds)
                                TextButton(onClick = { marketplaceViewModel.requestUninstall(entry) }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        service.connections.forEach { connection ->
                            CollapsibleToolConnectionCard(
                                connection = connection,
                                onEditClick = { onEditConnectionClick(connection.connectionUid) },
                                onRuntimeSettings = { pluginSettings = IntegratedPluginUi(ToolPluginId.connection(connection.connectionUid), connection.name, "", Icons.Rounded.Tune) },
                                onPermissionsClick = { permissionsConnection = connection },
                                onBrowseClick = { browsingConnection = connection },
                                onOAuthClick = { startOAuth(connection) },
                                onDeleteClick = { deletingConnection = connection },
                                health = uiState.connectionHealth[connection.connectionUid],
                                onRefreshHealth = {
                                    if (connection.type == ToolConnectionType.AMAZON_SERPAPI) {
                                        viewModel.testAmazonConnection(connection)
                                    } else {
                                        viewModel.probeConnections(listOf(connection), force = true)
                                    }
                                },
                                enabled = features.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid)),
                                onEnabledChange = { viewModel.setPluginEnabled(ToolPluginId.connection(connection.connectionUid), it) }
                            )
                        }
                    }
                }
            }
            marketplaceState.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
    dev.chungjungsoo.gptmobile.presentation.ui.mcp.NativePluginUninstallDialog(marketplaceViewModel)

    if (delegationSettingsOpen) {
        LocalToolConfigurationDialog(
            section = "delegation",
            onDismiss = { delegationSettingsOpen = false }
        )
    }

    pluginSettings?.let { plugin ->
        PluginConfigurationDialog(
            plugin.id,
            plugin.name,
            features,
            viewModel::updateFeature,
            onSave = { viewModel.configurePlugin(plugin.id, it) },
            onConnection = {
                val selected = uiState.connections.firstOrNull { ToolPluginId.connection(it.connectionUid) == plugin.id }
                    ?: uiState.connections.firstOrNull { it.type == if (plugin.id == ToolPluginId.AMAZON_SEARCH) ToolConnectionType.AMAZON_SERPAPI else ToolConnectionType.GITHUB }
                selected?.let { onEditConnectionClick(it.connectionUid) } ?: onAddConnectionClick()
            },
            onDismiss = { pluginSettings = null },
            onRevokePermissions = { viewModel.revokePluginGrants(plugin.id) },
            isAmazon = plugin.id == ToolPluginId.AMAZON_SEARCH || uiState.connections.any { ToolPluginId.connection(it.connectionUid) == plugin.id && it.type == ToolConnectionType.AMAZON_SERPAPI }
        )
    }

    deletingConnection?.let { connection ->
        val deleteDescription = stringResource(R.string.delete_named_connection, connection.name)
        AlertDialog(
            title = { Text(stringResource(R.string.delete_tool_connection)) },
            text = { Text(stringResource(R.string.delete_tool_connection_confirmation, connection.name)) },
            onDismissRequest = { deletingConnection = null },
            confirmButton = {
                TextButton(
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        viewModel.deleteConnection(connection.connectionUid)
                        deletingConnection = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingConnection = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            onDismissRequest = viewModel::clearError,
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

private data class IntegratedPluginUi(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector
)

private val INTEGRATED_PLUGINS = listOf(
    IntegratedPluginUi(ToolPluginId.MODEL_DELEGATION, "Model Delegation", "Hands bounded research and processing tasks to a configured delegate model.", Icons.Rounded.Psychology),
    IntegratedPluginUi(ToolPluginId.LOCAL_MEMORY, "Local Memory", "Recalls and captures private on-device memory and knowledge-graph context.", Icons.Rounded.Memory),
    IntegratedPluginUi(ToolPluginId.CURRENT_DATE, "Date & Time", "Provides current date context without a remote MCP server.", Icons.Rounded.Schedule),
    IntegratedPluginUi(ToolPluginId.CALCULATOR, "Calculator", "Evaluates arithmetic expressions locally.", Icons.Rounded.Calculate),
    IntegratedPluginUi(ToolPluginId.READ_FILES, "Read Files", "Reads bounded slices of files made available to the app.", Icons.Rounded.FolderOpen),
    IntegratedPluginUi(ToolPluginId.READ_URL, "Read URL", "Retrieves web pages through the app's native network stack.", Icons.Rounded.Language),
    IntegratedPluginUi(ToolPluginId.GITHUB, "GitHub API", "Uses the app's native GitHub REST integration for repository reads and writes.", Icons.Rounded.Code),
    IntegratedPluginUi(ToolPluginId.AMAZON_SEARCH, "Amazon Search", "Finds Amazon products with prices, ratings and product links. Add a SerpApi API key in Tool Connections.", Icons.Rounded.Storefront),
    IntegratedPluginUi(ToolPluginId.AMAZON_FREE, "Amazon Research Free", "Read public Amazon Canada, US, UK and France pages. No API key required. Preview access varies.", Icons.Rounded.Storefront),
    IntegratedPluginUi(ToolPluginId.WEB_SEARCH, "Web Search", "Uses integrated web-search providers without requiring an MCP server.", Icons.Rounded.Search),
    IntegratedPluginUi(ToolPluginId.DEVICE_LOCATION, "Device Location", "Provides device location only when the app and profile permissions allow it.", Icons.Rounded.LocationOn)
)

@Composable
private fun ToolProviderIcon(type: String, modifier: Modifier = Modifier) {
    val icon = when (type) {
        ToolConnectionType.MCP -> Icons.Rounded.Hub
        ToolConnectionType.FIRECRAWL -> Icons.Rounded.Language
        ToolConnectionType.PERPLEXITY -> Icons.Rounded.Search
        ToolConnectionType.EXA -> Icons.Rounded.Search
        ToolConnectionType.BRAVE -> Icons.Rounded.Search
        ToolConnectionType.GITHUB -> Icons.Rounded.Code
        ToolConnectionType.AMAZON_SERPAPI -> Icons.Rounded.Storefront
        else -> Icons.Rounded.Cable
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    ) {
        if (type == ToolConnectionType.AMAZON_SERPAPI) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.mcp_brand_amazon),
                contentDescription = "Amazon Search",
                modifier = Modifier.padding(6.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = providerLabel(type),
                modifier = Modifier.padding(6.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsibleToolConnectionCard(
    connection: ToolConnection,
    onEditClick: () -> Unit,
    onRuntimeSettings: () -> Unit = {},
    onPermissionsClick: () -> Unit,
    onBrowseClick: () -> Unit,
    showBrowseAction: Boolean = true,
    onOAuthClick: () -> Unit,
    onDeleteClick: () -> Unit,
    health: ToolConnectionHealth?,
    onRefreshHealth: () -> Unit,
    enabled: Boolean? = null,
    onEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(connection.connectionUid) { mutableStateOf(false) }
    val missingCredential = connection.authType != ToolConnectionAuthType.NONE && connection.secretRef == null
    val missingEndpoint = connection.endpointUrl.isNullOrBlank()
    val credentialStatus = when {
        connection.authType == ToolConnectionAuthType.NONE -> "No API key required"
        missingCredential && connection.authType == ToolConnectionAuthType.OAUTH -> "Sign in required"
        missingCredential -> "API key required"
        else -> "Credentials saved"
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(connection.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (connection.type == ToolConnectionType.MCP) "Remote MCP · ${connection.alias}" else "In-app provider · ${connection.alias}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                enabled?.let { Switch(checked = it, onCheckedChange = onEnabledChange, enabled = it || (!missingCredential && !missingEndpoint)) }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, if (expanded) "Collapse connection" else "Expand connection", Modifier.rotate(if (expanded) 180f else 0f))
                }
            }
            Text(if (missingEndpoint) "Endpoint required · $credentialStatus" else credentialStatus, color = if (missingCredential || missingEndpoint) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (connection.type == ToolConnectionType.MCP || health != null) ConnectionHealthLine(health)
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEditClick) {
                            Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                            Text("Setup", Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onRuntimeSettings) {
                            Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                            Text("Execution", Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onPermissionsClick) { Text("Permissions") }
                        if (connection.type == ToolConnectionType.MCP && showBrowseAction) OutlinedButton(onClick = onBrowseClick) { Text("Resources & prompts") }
                        if (connection.type == ToolConnectionType.GITHUB && showBrowseAction) OutlinedButton(onClick = onBrowseClick) { Text("Workspace") }
                        if (connection.authType == ToolConnectionAuthType.OAUTH) OutlinedButton(onClick = onOAuthClick) { Text(if (missingCredential) "Sign in" else "Reconnect") }
                    }
                    connection.endpointUrl?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (connection.type == ToolConnectionType.MCP || connection.type == ToolConnectionType.AMAZON_SERPAPI) {
                        TextButton(onClick = onRefreshHealth, enabled = health?.status != ToolConnectionHealthStatus.CHECKING && !missingCredential && !missingEndpoint) {
                            Text(if (connection.type == ToolConnectionType.AMAZON_SERPAPI) "Test Amazon Search · 1 request" else "Check connection")
                        }
                    }
                    TextButton(onClick = onDeleteClick) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Text("Remove connection", Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHealthLine(health: ToolConnectionHealth?) {
    val status = health?.status
    val dotColor = when (status) {
        ToolConnectionHealthStatus.ONLINE -> MaterialTheme.colorScheme.primary
        ToolConnectionHealthStatus.LIMITED -> MaterialTheme.colorScheme.secondary
        ToolConnectionHealthStatus.OFFLINE -> MaterialTheme.colorScheme.error
        ToolConnectionHealthStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
        null -> MaterialTheme.colorScheme.outline
    }
    val label = when (status) {
        ToolConnectionHealthStatus.ONLINE -> "Online"
        ToolConnectionHealthStatus.LIMITED -> "Limited"
        ToolConnectionHealthStatus.OFFLINE -> "Disconnected"
        ToolConnectionHealthStatus.CHECKING -> "Checking"
        null -> "Not checked"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).background(dotColor, CircleShape))
        Text(
            text = buildString {
                append(label)
                health?.toolCount?.let { append(" • $it tools") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolConnectionEditorScreen(
    modifier: Modifier = Modifier,
    connectionUid: String? = null,
    viewModel: ToolConnectionsViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit,
    onSaveComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connection = connectionUid?.let { uid -> uiState.connections.firstOrNull { it.connectionUid == uid } }
    val isEditing = connectionUid != null
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )

    val editingFlow = connection?.let(ToolConnectionSetupFlow::editing)

    if (isEditing && editingFlow == null) {
        Scaffold(
            modifier = modifier,
            topBar = {
                ToolConnectionEditorTopBar(
                    title = stringResource(R.string.edit_tool_connection),
                    scrollBehavior = scrollBehavior,
                    actionLabel = null,
                    isActionEnabled = false,
                    onNavigationClick = onNavigationClick,
                    onActionClick = {}
                )
            }
        ) { innerPadding ->
            Text(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(24.dp),
                text = stringResource(R.string.tool_connection_not_found)
            )
        }
        return
    }

    var setupFlow by remember(connection?.connectionUid) {
        mutableStateOf(editingFlow ?: ToolConnectionSetupFlow())
    }
    val title = stringResource(
        when {
            isEditing -> R.string.edit_tool_connection
            setupFlow.step == ToolConnectionSetupStep.CONNECTION_TYPE -> R.string.choose_connection_type
            setupFlow.step == ToolConnectionSetupStep.WEB_SEARCH_PROVIDER -> R.string.choose_search_provider
            setupFlow.step == ToolConnectionSetupStep.AUTHENTICATION -> R.string.authentication
            else -> R.string.connection_details
        }
    )
    var name by remember(connection?.connectionUid) { mutableStateOf(connection?.name.orEmpty()) }
    var alias by remember(connection?.connectionUid) { mutableStateOf(connection?.alias.orEmpty()) }
    var endpoint by remember(connection?.connectionUid) { mutableStateOf(connection?.endpointUrl.orEmpty()) }
    var authType by remember(connection?.connectionUid) { mutableStateOf(connection?.authType ?: ToolConnectionAuthType.NONE) }
    var credential by remember(connection?.connectionUid) { mutableStateOf("") }
    var oauthClientId by remember(connection?.connectionUid) { mutableStateOf(connection?.oauthClientId.orEmpty()) }
    var allowCleartext by remember(connection?.connectionUid) { mutableStateOf(connection?.allowCleartext == true) }
    var clearCredential by remember(connection?.connectionUid) { mutableStateOf(false) }
    val provider = setupFlow.provider
    val normalizedAlias = ToolConnectionsViewModel.normalizeAlias(alias)
    val isMcp = setupFlow.path == ToolConnectionSetupPath.MCP_SERVER
    val actualEndpoint = if (isMcp) endpoint else provider?.endpointUrl.orEmpty()
    val isEndpointValid = !isMcp || ToolConnectionsViewModel.isValidMcpEndpoint(actualEndpoint, allowCleartext)
    val detailsValid =
        name.isNotBlank() &&
            ToolConnectionsViewModel.isValidAlias(normalizedAlias) &&
            isEndpointValid
    val hasExistingCredential = connection?.secretRef != null
    val canPreserveCredential = connection?.let {
        hasExistingCredential &&
            it.type == provider?.type &&
            it.endpointUrl == actualEndpoint &&
            it.authType == authType
    } == true
    val credentialState = credentialEditState(
        hasExistingCredential = hasExistingCredential,
        canPreserveCredential = canPreserveCredential,
        credential = credential,
        clearCredential = clearCredential
    )
    val credentialValid = when {
        provider == null -> false
        !isMcp -> credentialState != CredentialEditState.MISSING
        authType == ToolConnectionAuthType.BEARER -> credentialState != CredentialEditState.MISSING
        else -> true
    }
    val isActionEnabled = when (setupFlow.step) {
        ToolConnectionSetupStep.CONNECTION_TYPE -> false
        ToolConnectionSetupStep.WEB_SEARCH_PROVIDER -> setupFlow.canContinue
        ToolConnectionSetupStep.DETAILS -> detailsValid && (!setupFlow.isSaveStep || credentialValid)
        ToolConnectionSetupStep.AUTHENTICATION -> detailsValid && credentialValid
    }
    val actionLabel = when {
        setupFlow.step == ToolConnectionSetupStep.CONNECTION_TYPE -> null
        setupFlow.isSaveStep -> stringResource(R.string.save)
        else -> stringResource(R.string.next)
    }
    val hasPreviousStep = setupFlow.step == ToolConnectionSetupStep.AUTHENTICATION ||
        (!isEditing && setupFlow.step != ToolConnectionSetupStep.CONNECTION_TYPE)
    val backFade = dev.chungjungsoo.gptmobile.presentation.common.rememberBackFade()
    val navigateBack: () -> Unit = {
        if (hasPreviousStep) {
            backFade.fade { setupFlow = setupFlow.back() }
        } else {
            onNavigationClick()
        }
    }
    BackHandler(enabled = hasPreviousStep, onBack = navigateBack)
    val save = {
        provider?.let { selectedProvider ->
            viewModel.saveConnection(
                connection,
                selectedProvider,
                name,
                alias,
                actualEndpoint,
                authType,
                credential,
                oauthClientId,
                allowCleartext,
                clearCredential,
                onSaveComplete
            )
        }
    }

    Scaffold(
        modifier = modifier.then(backFade.modifier),
        topBar = {
            ToolConnectionEditorTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                actionLabel = actionLabel,
                isActionEnabled = isActionEnabled,
                onNavigationClick = navigateBack,
                onActionClick = {
                    if (setupFlow.isSaveStep) {
                        save()
                    } else {
                        setupFlow = setupFlow.next()
                    }
                }
            )
        }
    ) { innerPadding ->
        ToolConnectionStepContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 24.dp),
            setupFlow = setupFlow,
            connection = connection,
            provider = provider,
            name = name,
            alias = alias,
            endpoint = actualEndpoint,
            authType = authType,
            credential = credential,
            oauthClientId = oauthClientId,
            allowCleartext = allowCleartext,
            clearCredential = clearCredential,
            isMcp = isMcp,
            isEndpointValid = isEndpointValid,
            onPathSelected = { path ->
                val previousPath = setupFlow.path
                setupFlow = setupFlow.selectPath(path).next()
                if (previousPath != null && previousPath != path) {
                    name = ""
                    alias = ""
                    endpoint = ""
                    credential = ""
                    oauthClientId = ""
                    allowCleartext = false
                    clearCredential = false
                }
                if (path == ToolConnectionSetupPath.MCP_SERVER) {
                    if (name.isBlank()) name = "MCP Server"
                    if (alias.isBlank()) alias = "mcp_server"
                    if (previousPath != path) {
                        authType = connection?.authType ?: ToolConnectionAuthType.NONE
                    }
                }
            },
            onProviderSelected = { option ->
                val previousProvider = setupFlow.provider
                setupFlow = setupFlow.selectWebProvider(option)
                if (name.isBlank() || name == previousProvider?.label) name = option.label
                if (alias.isBlank() ||
                    alias == previousProvider?.label?.let(ToolConnectionsViewModel::normalizeAlias)
                ) {
                    alias = ToolConnectionsViewModel.normalizeAlias(option.label)
                }
                if (previousProvider != null && previousProvider.type != option.type) {
                    credential = ""
                    clearCredential = false
                }
                endpoint = option.endpointUrl
                authType = option.authType
            },
            onNameChange = { name = it },
            onAliasChange = { alias = it },
            onEndpointChange = { endpoint = it },
            onAuthTypeChange = { authType = it },
            onCredentialChange = {
                credential = it
                if (it.isNotBlank()) clearCredential = false
            },
            onOAuthClientIdChange = { oauthClientId = it },
            onAllowCleartextChange = { allowCleartext = it },
            onClearCredentialChange = {
                clearCredential = it
                if (it) credential = ""
            }
        )
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            title = { Text(stringResource(R.string.error)) },
            text = { Text(message) },
            onDismissRequest = viewModel::clearError,
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolConnectionsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationClick: () -> Unit,
    onMarketplaceClick: () -> Unit,
    onAddClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = stringResource(R.string.tool_connections),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = onNavigationClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        actions = {
            IconButton(
                onClick = onMarketplaceClick
            ) {
                Icon(imageVector = Icons.Rounded.Storefront, contentDescription = "MCP Marketplace")
            }
            IconButton(
                onClick = onAddClick
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = stringResource(R.string.add_tool_connection))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

internal enum class CredentialEditState {
    KEEP,
    REPLACE,
    CLEAR,
    MISSING
}

internal fun credentialEditState(
    hasExistingCredential: Boolean,
    canPreserveCredential: Boolean,
    credential: String,
    clearCredential: Boolean
): CredentialEditState = when {
    clearCredential && hasExistingCredential -> CredentialEditState.CLEAR
    credential.isNotBlank() -> CredentialEditState.REPLACE
    hasExistingCredential && canPreserveCredential -> CredentialEditState.KEEP
    else -> CredentialEditState.MISSING
}

@Composable
private fun ToolConnectionStepContent(
    modifier: Modifier = Modifier,
    setupFlow: ToolConnectionSetupFlow,
    connection: ToolConnection?,
    provider: ToolConnectionProvider?,
    name: String,
    alias: String,
    endpoint: String,
    authType: String,
    credential: String,
    oauthClientId: String,
    allowCleartext: Boolean,
    clearCredential: Boolean,
    isMcp: Boolean,
    isEndpointValid: Boolean,
    onPathSelected: (ToolConnectionSetupPath) -> Unit,
    onProviderSelected: (ToolConnectionProvider) -> Unit,
    onNameChange: (String) -> Unit,
    onAliasChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onAuthTypeChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onOAuthClientIdChange: (String) -> Unit,
    onAllowCleartextChange: (Boolean) -> Unit,
    onClearCredentialChange: (Boolean) -> Unit
) {
    Column(modifier) {
        Spacer(modifier = Modifier.height(16.dp))
        when (setupFlow.step) {
            ToolConnectionSetupStep.CONNECTION_TYPE -> {
                Text(
                    text = stringResource(R.string.choose_connection_type_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                DestinationCard(
                    title = "Search, Shopping & GitHub APIs",
                    description = "Connect your own search or GitHub API credential. No custom remote host is needed.",
                    onClick = { onPathSelected(ToolConnectionSetupPath.WEB_SEARCH) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                DestinationCard(
                    title = stringResource(R.string.mcp_server),
                    description = stringResource(R.string.mcp_server_connection_description),
                    onClick = { onPathSelected(ToolConnectionSetupPath.MCP_SERVER) }
                )
            }

            ToolConnectionSetupStep.WEB_SEARCH_PROVIDER -> {
                Text(
                    text = "Choose an integrated API provider.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                ToolConnectionsViewModel.providers
                    .filterNot { it.type == ToolConnectionType.MCP }
                    .forEach { option ->
                        RadioItem(
                            title = option.label,
                            description = option.endpointUrl,
                            value = option.type,
                            selected = provider?.type == option.type
                        ) {
                            onProviderSelected(option)
                        }
                    }
            }

            ToolConnectionSetupStep.DETAILS -> ConnectionDetailsStep(
                connection = connection,
                provider = provider,
                name = name,
                alias = alias,
                endpoint = endpoint,
                credential = credential,
                allowCleartext = allowCleartext,
                clearCredential = clearCredential,
                isMcp = isMcp,
                isEndpointValid = isEndpointValid,
                onNameChange = onNameChange,
                onAliasChange = onAliasChange,
                onEndpointChange = onEndpointChange,
                onCredentialChange = onCredentialChange,
                onAllowCleartextChange = onAllowCleartextChange,
                onClearCredentialChange = onClearCredentialChange
            )

            ToolConnectionSetupStep.AUTHENTICATION -> AuthenticationStep(
                connection = connection,
                authType = authType,
                credential = credential,
                oauthClientId = oauthClientId,
                clearCredential = clearCredential,
                onAuthTypeChange = onAuthTypeChange,
                onCredentialChange = onCredentialChange,
                onOAuthClientIdChange = onOAuthClientIdChange,
                onClearCredentialChange = onClearCredentialChange
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionDetailsStep(
    connection: ToolConnection?,
    provider: ToolConnectionProvider?,
    name: String,
    alias: String,
    endpoint: String,
    credential: String,
    allowCleartext: Boolean,
    clearCredential: Boolean,
    isMcp: Boolean,
    isEndpointValid: Boolean,
    onNameChange: (String) -> Unit,
    onAliasChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onAllowCleartextChange: (Boolean) -> Unit,
    onClearCredentialChange: (Boolean) -> Unit
) {
    val normalizedAlias = ToolConnectionsViewModel.normalizeAlias(alias)
    val isAliasInvalid = alias.isNotBlank() && !ToolConnectionsViewModel.isValidAlias(normalizedAlias)
    val aliasError = stringResource(R.string.stable_alias_error)
    Text(
        text = if (provider?.type == ToolConnectionType.GITHUB) {
            "Connect your GitHub token for repositories, pull requests and Actions workflows. Token permissions control repository access; tool permissions control approvals for actions."
        } else {
            stringResource(
                if (isMcp) R.string.mcp_details_description else R.string.web_search_details_description,
                provider?.label.orEmpty()
            )
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics {
                if (isAliasInvalid) error(aliasError)
            },
        value = alias,
        onValueChange = onAliasChange,
        label = { Text(stringResource(R.string.stable_alias)) },
        isError = isAliasInvalid,
        supportingText = {
            Text(if (isAliasInvalid) aliasError else stringResource(R.string.stable_alias_description))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
    )
    if (isMcp) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            value = endpoint,
            onValueChange = onEndpointChange,
            label = { Text(stringResource(R.string.api_url)) },
            isError = endpoint.isNotBlank() && !isEndpointValid,
            supportingText = if (endpoint.isNotBlank() && !isEndpointValid) {
                { Text(stringResource(R.string.mcp_endpoint_error)) }
            } else {
                null
            },
            singleLine = true
        )
        if (endpoint.startsWith("http://", ignoreCase = true)) {
            LabeledCheckbox(
                checked = allowCleartext,
                label = stringResource(R.string.cleartext_mcp_warning),
                contentDescription = stringResource(R.string.allow_cleartext_mcp_endpoint),
                onCheckedChange = onAllowCleartextChange
            )
        }
    } else {
        Text(
            text = provider?.endpointUrl.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        CredentialField(
            connection = connection,
            credential = credential,
            label = stringResource(R.string.api_key),
            clearCredential = clearCredential,
            onCredentialChange = onCredentialChange,
            onClearCredentialChange = onClearCredentialChange
        )
        if (provider?.type == ToolConnectionType.GITHUB) {
            Text(
                text = "For repository writes, use a credential with Contents: read/write. Creating pull requests also needs Pull requests: read/write. Dispatching or rerunning workflows needs Actions: read/write. A 403 can also come from branch protection, SSO, installation policy, or repository rules.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (provider?.type == ToolConnectionType.BRAVE) {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Text(
                text = stringResource(R.string.brave_search_setup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = { uriHandler.openUri("https://api-dashboard.search.brave.com/app/keys") }) {
                Text(stringResource(R.string.brave_search_get_api_key))
            }
        }
        if (provider?.type == ToolConnectionType.AMAZON_SERPAPI) {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Text("Use your own SerpApi API key. Search queries are sent to SerpApi; its account limits and pricing apply. Choose the marketplace in Amazon Search settings after saving.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { uriHandler.openUri("https://serpapi.com/") }) { Text("Get a SerpApi API key") }
        }
    }
}

@Composable
private fun AuthenticationStep(
    connection: ToolConnection?,
    authType: String,
    credential: String,
    oauthClientId: String,
    clearCredential: Boolean,
    onAuthTypeChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onOAuthClientIdChange: (String) -> Unit,
    onClearCredentialChange: (Boolean) -> Unit
) {
    val bearerToken = stringResource(R.string.bearer_token)
    Text(
        text = stringResource(R.string.mcp_authentication_description),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 16.dp)
    )
    listOf(
        ToolConnectionAuthType.NONE to stringResource(R.string.public_access),
        ToolConnectionAuthType.BEARER to bearerToken,
        ToolConnectionAuthType.OAUTH to stringResource(R.string.oauth_pkce)
    ).forEach { (value, label) ->
        RadioItem(
            title = label,
            description = null,
            value = value,
            selected = authType == value
        ) {
            onAuthTypeChange(value)
        }
    }
    if (authType == ToolConnectionAuthType.BEARER) {
        CredentialField(
            connection = connection,
            credential = credential,
            label = bearerToken,
            clearCredential = clearCredential,
            onCredentialChange = onCredentialChange,
            onClearCredentialChange = onClearCredentialChange
        )
    }
    if (authType == ToolConnectionAuthType.OAUTH) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            value = oauthClientId,
            onValueChange = onOAuthClientIdChange,
            label = { Text(stringResource(R.string.preregistered_client_id_optional)) },
            supportingText = { Text(stringResource(R.string.dynamic_client_registration_hint)) },
            singleLine = true
        )
        if (connection?.secretRef != null) {
            LabeledCheckbox(
                checked = clearCredential,
                label = stringResource(R.string.clear_saved_credential),
                contentDescription = stringResource(R.string.clear_saved_credential),
                onCheckedChange = onClearCredentialChange
            )
        }
    }
}

@Composable
private fun CredentialField(
    connection: ToolConnection?,
    credential: String,
    label: String,
    clearCredential: Boolean,
    onCredentialChange: (String) -> Unit,
    onClearCredentialChange: (Boolean) -> Unit
) {
    val initialKeys = remember(credential) {
        val parsed = ApiCredentialRotator.parseKeys(credential)
        if (parsed.isEmpty()) listOf("") else parsed
    }
    val credentialKeys = remember { mutableStateListOf<String>().apply { addAll(initialKeys) } }

    fun syncCredential() {
        onCredentialChange(ApiCredentialRotator.formatKeys(credentialKeys.toList()))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.multi_api_keys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        credentialKeys.forEachIndexed { index, keyVal ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = keyVal,
                    onValueChange = { newVal ->
                        credentialKeys[index] = newVal
                        syncCredential()
                    },
                    label = {
                        Text(
                            if (credentialKeys.size > 1) {
                                stringResource(R.string.api_key_number, index + 1)
                            } else {
                                label
                            }
                        )
                    },
                    supportingText = {
                        Text(
                            if (connection?.secretRef == null) {
                                stringResource(R.string.credential_not_set)
                            } else {
                                stringResource(R.string.blank_key_preserves_credential)
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visualTransformation = PasswordVisualTransformation()
                )
                if (credentialKeys.size > 1) {
                    IconButton(
                        onClick = {
                            credentialKeys.removeAt(index)
                            syncCredential()
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.remove_api_key),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    credentialKeys.add("")
                    syncCredential()
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add_api_key),
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(stringResource(R.string.add_api_key))
            }
        }
        if (connection?.secretRef != null) {
            LabeledCheckbox(
                checked = clearCredential,
                label = stringResource(R.string.clear_saved_credential),
                contentDescription = stringResource(R.string.clear_saved_credential),
                onCheckedChange = onClearCredentialChange
            )
        }
    }
}

@Composable
private fun LabeledCheckbox(
    checked: Boolean,
    label: String,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription }
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(top = 8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = label
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolConnectionEditorTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actionLabel: String?,
    isActionEnabled: Boolean,
    onNavigationClick: () -> Unit,
    onActionClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = onNavigationClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        actions = {
            actionLabel?.let { label ->
                TextButton(
                    modifier = Modifier.semantics { contentDescription = label },
                    enabled = isActionEnabled,
                    onClick = onActionClick
                ) {
                    Text(label)
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

private fun providerLabel(type: String): String = ToolConnectionsViewModel.providers.firstOrNull { it.type == type }?.label ?: type
