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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
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
    var remoteMcpTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pluginSettings by remember { mutableStateOf<IntegratedPluginUi?>(null) }
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

    Scaffold(
        modifier = modifier,
        topBar = {
            ToolConnectionsTopBar(
                scrollBehavior = scrollBehavior,
                onNavigationClick = onNavigationClick,
                onMarketplaceClick = onMarketplaceClick,
                onAddClick = onAddConnectionClick
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = if (remoteMcpTab) 1 else 0) {
                androidx.compose.material3.Tab(
                    selected = !remoteMcpTab,
                    onClick = { remoteMcpTab = false },
                    text = { Text("Plugins") }
                )
                androidx.compose.material3.Tab(
                    selected = remoteMcpTab,
                    onClick = { remoteMcpTab = true },
                    text = { Text("Remote MCP") }
                )
            }

            SettingsHero(if (remoteMcpTab) "Connected Services" else "Your Toolkit", if (remoteMcpTab) "Remote MCP" else "Plugins", "${uiState.connections.size} connections · ${INTEGRATED_PLUGINS.size} built-in plugins", Modifier.padding(16.dp))
            OutlinedTextField(search, { search = it }, label = { Text(if (remoteMcpTab) "Find a connection" else "Find a plugin") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val matchingConnections = uiState.connections.filter { search.isBlank() || "$it".contains(search, true) }
            val nativeConnections = matchingConnections.filter { it.type != ToolConnectionType.MCP }
            val mcpConnections = matchingConnections.filter { it.type == ToolConnectionType.MCP }
            val hasMcpConnection = uiState.connections.any { it.type == ToolConnectionType.MCP }

            if (!remoteMcpTab) {
                dev.chungjungsoo.gptmobile.presentation.ui.mcp.InstalledNativePluginsPanel(search)
                Text(
                    text = "Integrated Plugins",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Built-In Tools", style = MaterialTheme.typography.labelSmall)
                    SettingsHelpIcon("Built-in plugins are available immediately. Disable a plugin to remove its tools from model sessions.")
                }
                INTEGRATED_PLUGINS.filter { search.isBlank() || "${it.name} ${it.description}".contains(search, true) }.forEach { plugin ->
                    val enabled = uiState.pluginStates[plugin.id] ?: true
                    IntegratedPluginCard(
                        plugin = plugin,
                        enabled = enabled,
                        onEnabledChange = { viewModel.setPluginEnabled(plugin.id, it) },
                        onSettings = {
                            when (plugin.id) {
                                ToolPluginId.MODEL_DELEGATION -> delegationSettingsOpen = true
                                ToolPluginId.LOCAL_MEMORY -> memorySettingsOpen = true
                                else -> pluginSettings = plugin
                            }
                        }
                    )
                }

                if (nativeConnections.isNotEmpty()) {
                    Text(
                        text = "Configured Plugins",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    nativeConnections.forEach { connection ->
                        val pluginId = ToolPluginId.connection(connection.connectionUid)
                        CollapsibleToolConnectionCard(
                            connection = connection,
                            onEditClick = { onEditConnectionClick(connection.connectionUid) },
                            onRuntimeSettings = { pluginSettings = IntegratedPluginUi(ToolPluginId.connection(connection.connectionUid), connection.name, "", Icons.Rounded.Tune) },
                            onPermissionsClick = { permissionsConnection = connection },
                            onBrowseClick = { browsingConnection = connection },
                            showBrowseAction = connection.type != ToolConnectionType.GITHUB ||
                                ((uiState.pluginStates[ToolPluginId.GITHUB] ?: true) && (uiState.pluginStates[pluginId] ?: true)),
                            onOAuthClick = {
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
                            },
                            onDeleteClick = { deletingConnection = connection },
                            health = uiState.connectionHealth[connection.connectionUid],
                            onRefreshHealth = { if (connection.type == ToolConnectionType.AMAZON_SERPAPI) viewModel.testAmazonConnection(connection) },
                            enabled = uiState.pluginStates[pluginId] ?: true,
                            onEnabledChange = { viewModel.setPluginEnabled(pluginId, it) }
                        )
                    }
                }
            } else {
                RemoteMcpMasterCard(
                    enabled = uiState.remoteMcpEnabled,
                    onEnabledChange = viewModel::setRemoteMcpEnabled
                )

                if (!hasMcpConnection) {
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Browse MCP Marketplace", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                SettingsHelpIcon("Discover remote MCP servers and connect their advertised tools.")
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Storefront,
                                contentDescription = "MCP Marketplace",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = onMarketplaceClick) { Text("Explore") }
                        }
                    )
                }
                TextButton(onClick = { pairingLink = "" }) {
                    Text(stringResource(R.string.pair_server_title))
                }

                if (mcpConnections.isEmpty()) {
                    Text(
                        text = "No remote MCP servers are connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
                mcpConnections.forEach { connection ->
                    CollapsibleToolConnectionCard(
                        connection = connection,
                        onRuntimeSettings = { pluginSettings = IntegratedPluginUi(ToolPluginId.connection(connection.connectionUid), connection.name, "", Icons.Rounded.Tune) },
                        onEditClick = { onEditConnectionClick(connection.connectionUid) },
                        onPermissionsClick = { permissionsConnection = connection },
                        onBrowseClick = { browsingConnection = connection },
                        onOAuthClick = {
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
                        },
                        onDeleteClick = { deletingConnection = connection },
                        health = uiState.connectionHealth[connection.connectionUid],
                        onRefreshHealth = { viewModel.probeConnections(listOf(connection), force = true) }
                    )
                }
            }
        }
    }

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
    IntegratedPluginUi(ToolPluginId.WEB_SEARCH, "Web Search", "Uses integrated web-search providers without requiring an MCP server.", Icons.Rounded.Search),
    IntegratedPluginUi(ToolPluginId.DEVICE_LOCATION, "Device Location", "Provides device location only when the app and profile permissions allow it.", Icons.Rounded.LocationOn)
)

@Composable
private fun IntegratedPluginCard(
    plugin: IntegratedPluginUi,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntegratedPluginIcon(plugin)
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    SettingsHelpIcon(plugin.description)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun IntegratedPluginIcon(plugin: IntegratedPluginUi) {
    val colors = MaterialTheme.colorScheme
    val gradient = when (plugin.id) {
        ToolPluginId.MODEL_DELEGATION -> listOf(colors.primaryContainer, colors.tertiaryContainer)
        ToolPluginId.LOCAL_MEMORY -> listOf(colors.secondaryContainer, colors.primaryContainer)
        ToolPluginId.GITHUB -> listOf(colors.surfaceVariant, colors.primaryContainer)
        ToolPluginId.WEB_SEARCH -> listOf(colors.tertiaryContainer, colors.secondaryContainer)
        ToolPluginId.DEVICE_LOCATION -> listOf(colors.primaryContainer, colors.secondaryContainer)
        else -> listOf(colors.surfaceContainerHighest, colors.primaryContainer)
    }
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = colors.surface.copy(alpha = 0.72f),
            contentColor = colors.primary,
            tonalElevation = 4.dp
        ) {
            Icon(
                plugin.icon,
                contentDescription = null,
                modifier = Modifier.padding(9.dp).size(25.dp)
            )
        }
        Surface(
            shape = CircleShape,
            color = colors.primary,
            contentColor = colors.onPrimary,
            shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
private fun RemoteMcpMasterCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remote MCP Servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SettingsHelpIcon("Allow profiles to discover and call tools hosted by configured MCP servers.")
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

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
        Icon(
            imageVector = icon,
            contentDescription = providerLabel(type),
            modifier = Modifier.padding(6.dp).size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

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
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "toolConnectionArrow"
    )

    val credentialStatus = when {
        connection.authType == ToolConnectionAuthType.NONE -> stringResource(R.string.public_access)
        connection.authType == ToolConnectionAuthType.OAUTH && connection.secretRef == null -> stringResource(R.string.oauth_not_connected)
        connection.authType == ToolConnectionAuthType.OAUTH -> stringResource(R.string.oauth_connected)
        connection.secretRef == null -> stringResource(R.string.credential_not_set)
        else -> stringResource(R.string.credential_set)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolProviderIcon(type = connection.type)

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${providerLabel(connection.type)} • ${connection.alias}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (connection.type == ToolConnectionType.MCP || (connection.type == ToolConnectionType.AMAZON_SERPAPI && health != null)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        ConnectionHealthLine(health)
                    }
                }

                enabled?.let { isEnabled ->
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onEnabledChange
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        connection.authType == ToolConnectionAuthType.NONE -> MaterialTheme.colorScheme.surfaceContainerHighest
                        connection.authType == ToolConnectionAuthType.OAUTH && connection.secretRef != null -> MaterialTheme.colorScheme.primaryContainer
                        connection.secretRef != null -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    }
                ) {
                    Text(
                        text = credentialStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            connection.authType == ToolConnectionAuthType.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            connection.authType == ToolConnectionAuthType.OAUTH && connection.secretRef != null -> MaterialTheme.colorScheme.onPrimaryContainer
                            connection.secretRef != null -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(arrowRotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    if (connection.type == ToolConnectionType.MCP && showBrowseAction) TextButton(onClick = onBrowseClick) { Text("Resources And Prompts") }
                    TextButton(onClick = onRuntimeSettings) { Text("Execution Settings") }
                    if (connection.type == ToolConnectionType.GITHUB && showBrowseAction) TextButton(onClick = onBrowseClick) { Text("Open GitHub Workspace") }
                    if (connection.type in setOf(ToolConnectionType.MCP, ToolConnectionType.GITHUB, ToolConnectionType.AMAZON_SERPAPI)) TextButton(onClick = onPermissionsClick) { Text(stringResource(R.string.tool_policy)) }
                    if (connection.type == ToolConnectionType.AMAZON_SERPAPI) {
                        Text(health?.message ?: "Test sends a sample search to SerpApi and uses one provider request.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = health?.status != ToolConnectionHealthStatus.CHECKING, onClick = onRefreshHealth) { Text("Test Amazon Search · 1 request") }
                    }
                    connection.endpointUrl?.let { url ->
                        if (url.isNotBlank()) {
                            Text(
                                text = "Endpoint: $url",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    Text(
                        text = "Authentication: ${connection.authType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (connection.type == ToolConnectionType.MCP) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = health?.message ?: "Health has not been checked yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (connection.type == ToolConnectionType.MCP) {
                            TextButton(onClick = onRefreshHealth) {
                                Text("Test")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (connection.type == ToolConnectionType.MCP && connection.authType == ToolConnectionAuthType.OAUTH) {
                            TextButton(onClick = onOAuthClick) {
                                Text(stringResource(if (connection.secretRef == null) R.string.connect else R.string.reconnect))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(onClick = onEditClick) {
                            Icon(imageVector = Icons.Rounded.Edit, contentDescription = "Edit Connection")
                        }
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Delete Connection",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegratedToolsCard() {
    val tools = listOf(
        "Date & time",
        "Calculator",
        "Read files",
        "Read URL",
        "GitHub",
        "Device location"
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { name ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                Modifier.padding(9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Box(
                                    Modifier.size(8.dp).background(Color(0xFF2E7D32), CircleShape)
                                )
                                Text(name, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConnectionHealthLine(health: ToolConnectionHealth?) {
    val status = health?.status
    val dotColor = when (status) {
        ToolConnectionHealthStatus.ONLINE -> Color(0xFF2E7D32)
        ToolConnectionHealthStatus.LIMITED -> Color(0xFFF9A825)
        ToolConnectionHealthStatus.OFFLINE -> Color(0xFFC62828)
        ToolConnectionHealthStatus.CHECKING -> Color(0xFF1976D2)
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
