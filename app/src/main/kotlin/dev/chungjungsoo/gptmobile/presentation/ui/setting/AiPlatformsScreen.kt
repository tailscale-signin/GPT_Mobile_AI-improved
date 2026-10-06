package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.parseProfileLabels
import dev.chungjungsoo.gptmobile.presentation.common.BeveledProfileLabel
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPlatformsScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToOpenRouterSettings: () -> Unit = {},
    onNavigateToProviderSettings: (String) -> Unit = {},
    onNavigateToPlatformSetting: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms by settingViewModel.platformState.collectAsStateWithLifecycle()
    val providerConnections by settingViewModel.providerConnections.collectAsStateWithLifecycle()
    var selectedPlatformTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.ai_platforms)) },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAddPlatform,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add_platform)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PrimaryTabRow(selectedTabIndex = selectedPlatformTab) {
                    Tab(
                        selected = selectedPlatformTab == 0,
                        onClick = { selectedPlatformTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Cloud, null, modifier = Modifier.size(18.dp))
                                Text("Remote")
                            }
                        }
                    )
                    Tab(
                        selected = selectedPlatformTab == 1,
                        onClick = { selectedPlatformTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.Dns, null, modifier = Modifier.size(18.dp))
                                Text("Local")
                            }
                        }
                    )
                    Tab(
                        selected = selectedPlatformTab == 2,
                        onClick = { selectedPlatformTab = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                                Text("Free")
                            }
                        }
                    )
                }
            }
            if (platforms.isEmpty() && providerConnections.isEmpty()) {
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
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.no_platforms_yet),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = onNavigateToAddPlatform) {
                                Text(stringResource(R.string.add_your_first_platform))
                            }
                        }
                    }
                }
            } else {
                val freeConnections = providerConnections.filter { it.compatibleType == ClientType.FREE }
                val freeStandalone = platforms.filter { it.compatibleType == ClientType.FREE && it.providerConnectionUid == null }
                if (selectedPlatformTab == 2) {
                    items(freeConnections, key = { "connection:${it.uid}" }) { connection ->
                        ProviderConnectionGroupCard(
                            modifier = Modifier.animateItem(),
                            connection = connection,
                            profiles = platforms.filter { it.providerConnectionUid == connection.uid },
                            onToggleFavorite = { settingViewModel.togglePlatformFavorite(it.id) },
                            onEdit = { onNavigateToPlatformSetting(it.uid) },
                            onProviderSettings = { onNavigateToProviderSettings(connection.uid) }
                        )
                    }
                    items(freeStandalone, key = { "profile:${it.id}" }) { platform ->
                        PlatformItemCard(
                            modifier = Modifier.animateItem(),
                            platform = platform,
                            onToggleFavorite = { settingViewModel.togglePlatformFavorite(platform.id) },
                            onEdit = { onNavigateToPlatformSetting(platform.uid) }
                        )
                    }
                }
                val localTypes = setOf(ClientType.LITERT_LM, ClientType.LLAMA, ClientType.OLLAMA)
                val localConnections = providerConnections.filter { it.compatibleType in localTypes }
                val remoteConnections = providerConnections.filter { it.compatibleType != ClientType.FREE && it.compatibleType !in localTypes }
                val localStandalone = platforms.filter { it.providerConnectionUid == null && it.compatibleType in localTypes }
                val remoteStandalone = platforms.filter { it.providerConnectionUid == null && it.compatibleType != ClientType.FREE && it.compatibleType !in localTypes }

                if (selectedPlatformTab == 0) {
                    items(remoteConnections, key = { "remote-connection:${it.uid}" }) { connection ->
                        ProviderConnectionGroupCard(
                            modifier = Modifier.animateItem(),
                            connection = connection,
                            profiles = platforms.filter { it.providerConnectionUid == connection.uid },
                            onToggleFavorite = { settingViewModel.togglePlatformFavorite(it.id) },
                            onEdit = { onNavigateToPlatformSetting(it.uid) },
                            onProviderSettings = { onNavigateToProviderSettings(connection.uid) },
                            onSpecialSettings = if (connection.compatibleType == ClientType.OPENROUTER) onNavigateToOpenRouterSettings else null
                        )
                    }
                    items(remoteStandalone, key = { "remote-profile:${it.id}" }) { platform ->
                        PlatformItemCard(platform, { settingViewModel.togglePlatformFavorite(platform.id) }, { onNavigateToPlatformSetting(platform.uid) }, modifier = Modifier.animateItem())
                    }
                }

                if (selectedPlatformTab == 1) {
                    items(localConnections, key = { "local-connection:${it.uid}" }) { connection ->
                        ProviderConnectionGroupCard(
                            modifier = Modifier.animateItem(),
                            connection = connection,
                            profiles = platforms.filter { it.providerConnectionUid == connection.uid },
                            onToggleFavorite = { settingViewModel.togglePlatformFavorite(it.id) },
                            onEdit = { onNavigateToPlatformSetting(it.uid) },
                            onProviderSettings = { onNavigateToProviderSettings(connection.uid) }
                        )
                    }
                    items(localStandalone, key = { "local-profile:${it.id}" }) { platform ->
                        PlatformItemCard(platform, { settingViewModel.togglePlatformFavorite(platform.id) }, { onNavigateToPlatformSetting(platform.uid) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConnectionGroupCard(
    connection: ProviderConnection,
    profiles: List<PlatformV2>,
    onToggleFavorite: (PlatformV2) -> Unit,
    onEdit: (PlatformV2) -> Unit,
    onProviderSettings: (() -> Unit)? = null,
    onSpecialSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(connection.uid) { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide Profiles" else "Show Profiles", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (onProviderSettings != null) {
                        IconButton(onClick = onProviderSettings) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Provider Settings",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            if (expanded) onSpecialSettings?.let { action -> TextButton(onClick = action) { Text("OpenRouter Options") } }
            if (expanded && connection.hasCredential) {
                Text(
                    text = stringResource(R.string.credential_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                if (profiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_profiles_for_connection),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        profiles.forEach { platform ->
                            PlatformItemCard(
                                platform = platform,
                                onToggleFavorite = { onToggleFavorite(platform) },
                                onEdit = { onEdit(platform) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlatformItemCard(
    platform: PlatformV2,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val labelsList = remember(platform.labels) { parseProfileLabels(platform.labels) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (platform.enabled) 1f else 0.4f }
            .combinedClickable(
                onClick = onEdit,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFavorite()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = platform.name.ifBlank { platform.compatibleType.name },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (platform.isFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = platform.model.ifBlank { "Default Model" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (labelsList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        labelsList.forEach { label ->
                            BeveledProfileLabel(label = label)
                        }
                    }
                }
            }
        }
    }
}
