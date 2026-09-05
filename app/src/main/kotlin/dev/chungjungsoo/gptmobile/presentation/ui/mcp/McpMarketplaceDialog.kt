package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.catalog.McpCategory
import dev.chungjungsoo.gptmobile.data.catalog.McpPreset
import dev.chungjungsoo.gptmobile.data.catalog.McpPresetCatalog
import dev.chungjungsoo.gptmobile.data.catalog.McpPricingType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ToolConnectionsViewModel
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior

/**
 * Full-screen MCP Marketplace page allowing users to discover, inspect, configure,
 * and validate all required parameters for MCP tools before adding them to their tool connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<McpCategory?>(null) }
    var selectedPricing by remember { mutableStateOf<McpPricingType?>(null) }
    var configuringPreset by remember { mutableStateOf<McpPreset?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val filteredPresets = remember(searchQuery, selectedCategory, selectedPricing) {
        var list = McpPresetCatalog.presets

        if (selectedCategory != null) {
            list = list.filter { it.category == selectedCategory }
        }
        if (selectedPricing != null) {
            list = list.filter { it.pricing == selectedPricing }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.alias.lowercase().contains(q) ||
                    it.category.displayName.lowercase().contains(q) ||
                    it.pricing.displayName.lowercase().contains(q) ||
                    it.toolCapabilities.any { tool -> tool.lowercase().contains(q) }
            }
        }
        list
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            McpMarketplaceTopBar(
                scrollBehavior = scrollBehavior,
                onNavigationClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar & Filter Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search MCP tools, capabilities, keywords…") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Pricing Filter Chips (Free, Free with sign up, Paid)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Price:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilterChip(
                        selected = selectedPricing == null,
                        onClick = { selectedPricing = null },
                        label = { Text("All Prices") },
                        shape = RoundedCornerShape(12.dp)
                    )

                    McpPricingType.entries.forEach { pricing ->
                        FilterChip(
                            selected = selectedPricing == pricing,
                            onClick = {
                                selectedPricing = if (selectedPricing == pricing) null else pricing
                            },
                            label = { Text(pricing.displayName) },
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = {
                                PricingIcon(pricing = pricing)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Category Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Category:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") },
                        shape = RoundedCornerShape(12.dp)
                    )

                    McpCategory.entries.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = if (selectedCategory == category) null else category
                            },
                            label = { Text(category.displayName) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Results summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${filteredPresets.size} MCP integration${if (filteredPresets.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Presets Cards List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredPresets, key = { it.id }) { preset ->
                    val isInstalled = installedAliases.contains(preset.alias) ||
                        installedAliases.contains(ToolConnectionsViewModel.normalizeAlias(preset.alias))

                    McpMarketplaceDetailCard(
                        preset = preset,
                        isInstalled = isInstalled,
                        onAddClick = { configuringPreset = preset }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // Modal Configuration Dialog to ensure all needed fields are filled in
    configuringPreset?.let { preset ->
        McpPresetConfigureDialog(
            preset = preset,
            onDismissRequest = { configuringPreset = null },
            onConfirm = { name, alias, endpoint, authType, credential, allowCleartext ->
                onInstallPresetWithConfig(
                    preset,
                    name,
                    alias,
                    endpoint,
                    authType,
                    credential,
                    allowCleartext
                )
                configuringPreset = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpMarketplaceTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Column {
                Text(
                    text = "MCP Marketplace",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Connect high-capability autonomous tools to your AI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.go_back)
                )
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun PricingBadge(pricing: McpPricingType) {
    val (bgColor, textColor) = when (pricing) {
        McpPricingType.FREE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        McpPricingType.FREE_WITH_SIGNUP -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        McpPricingType.PAID -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PricingIcon(pricing = pricing, modifier = Modifier.size(12.dp), tint = textColor)
            Text(
                text = pricing.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

@Composable
fun PricingIcon(pricing: McpPricingType, modifier: Modifier = Modifier.size(16.dp), tint: Color = MaterialTheme.colorScheme.primary) {
    when (pricing) {
        McpPricingType.FREE -> Icon(Icons.Default.Check, contentDescription = null, modifier = modifier, tint = tint)
        McpPricingType.FREE_WITH_SIGNUP -> Icon(Icons.Default.Key, contentDescription = null, modifier = modifier, tint = tint)
        McpPricingType.PAID -> Icon(Icons.Default.Star, contentDescription = null, modifier = modifier, tint = tint)
    }
}

@Composable
fun McpMarketplaceDetailCard(
    preset: McpPreset,
    isInstalled: Boolean,
    onAddClick: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header Row: Title, Badges, Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PricingBadge(pricing = preset.pricing)

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = preset.category.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Text(
                            text = "by ${preset.author}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (isInstalled) {
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Installed",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Installed")
                    }
                } else {
                    Button(
                        onClick = onAddClick,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Full Description
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Tool capabilities list
            if (preset.toolCapabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Tools Provided:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))

                preset.toolCapabilities.take(if (isExpanded) preset.toolCapabilities.size else 2).forEach { toolDesc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = toolDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Expand / Collapse Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preset.websiteUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preset.websiteUrl))
                            runCatching { context.startActivity(intent) }
                        }
                    ) {
                        Text(
                            text = "Docs / Portal",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open docs",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (preset.toolCapabilities.size > 2) {
                    TextButton(onClick = { isExpanded = !isExpanded }) {
                        Text(
                            text = if (isExpanded) "Show Less" else "Show All (${preset.toolCapabilities.size} tools)",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Ensures all required fields (Name, Alias, Endpoint URL, and API/Bearer Tokens) are validated
 * before an MCP preset can be added to the connection pool.
 */
@Composable
fun McpPresetConfigureDialog(
    preset: McpPreset,
    onDismissRequest: () -> Unit,
    onConfirm: (
        name: String,
        alias: String,
        endpoint: String,
        authType: String,
        credential: String,
        allowCleartext: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf(preset.name) }
    var alias by remember { mutableStateOf(preset.alias) }
    var endpoint by remember { mutableStateOf(preset.defaultEndpoint) }
    var authType by remember { mutableStateOf(preset.suggestedAuthType) }
    var credential by remember { mutableStateOf("") }
    var allowCleartext by remember {
        mutableStateOf(preset.defaultEndpoint.startsWith("http://", ignoreCase = true))
    }

    val normalizedAlias = ToolConnectionsViewModel.normalizeAlias(alias)
    val isAliasValid = ToolConnectionsViewModel.isValidAlias(normalizedAlias)
    val isNameValid = name.isNotBlank()
    val isEndpointValid = ToolConnectionsViewModel.isValidMcpEndpoint(endpoint.trim(), allowCleartext)

    val isCredentialRequired = authType == ToolConnectionAuthType.BEARER ||
        preset.pricing == McpPricingType.PAID ||
        (preset.pricing == McpPricingType.FREE_WITH_SIGNUP && authType != ToolConnectionAuthType.NONE)

    val isCredentialValid = !isCredentialRequired || credential.isNotBlank()

    val canSave = isNameValid && isAliasValid && isEndpointValid && isCredentialValid

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Add ${preset.name}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure and verify required connection settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Connection Name *") },
                    isError = !isNameValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stable Alias
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("Tool Alias * (lowercase, letters/numbers/_)") },
                    isError = alias.isNotBlank() && !isAliasValid,
                    supportingText = {
                        if (alias.isNotBlank() && !isAliasValid) {
                            Text("Must start with a letter and contain only [a-z0-9_]")
                        } else {
                            Text("Used by AI agents as the namespace prefix")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Endpoint URL
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("MCP SSE Endpoint URL *") },
                    isError = endpoint.isNotBlank() && !isEndpointValid,
                    supportingText = {
                        if (endpoint.isNotBlank() && !isEndpointValid) {
                            Text("Must be a valid HTTP(S) URL. Cleartext requires explicit approval.")
                        } else {
                            Text("Server-Sent Events endpoint exposing MCP protocol")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                if (endpoint.startsWith("http://", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { allowCleartext = !allowCleartext }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allowCleartext,
                            onCheckedChange = { allowCleartext = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Allow cleartext HTTP connection (Local loopback / private network)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Authentication section
                Text(
                    text = "Authentication",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = authType == ToolConnectionAuthType.NONE,
                        onClick = { authType = ToolConnectionAuthType.NONE },
                        label = { Text("None / Public") }
                    )
                    FilterChip(
                        selected = authType == ToolConnectionAuthType.BEARER,
                        onClick = { authType = ToolConnectionAuthType.BEARER },
                        label = { Text("Bearer / API Key") }
                    )
                }

                if (authType == ToolConnectionAuthType.BEARER) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { credential = it },
                        label = {
                            Text(
                                if (preset.requiredFields.isNotEmpty()) {
                                    "${preset.requiredFields.first()} *"
                                } else {
                                    "API Key / Bearer Token *"
                                }
                            )
                        },
                        isError = isCredentialRequired && credential.isBlank(),
                        supportingText = {
                            if (isCredentialRequired && credential.isBlank()) {
                                Text("This integration requires an API key or token to authenticate.")
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            onConfirm(
                                name.trim(),
                                normalizedAlias,
                                endpoint.trim(),
                                authType,
                                credential.trim(),
                                allowCleartext
                            )
                        },
                        enabled = canSave,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add to Connections")
                    }
                }
            }
        }
    }
}
