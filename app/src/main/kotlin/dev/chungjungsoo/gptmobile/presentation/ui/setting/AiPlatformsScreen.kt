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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiPlatformsScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onNavigateToAddPlatform: () -> Unit,
    onNavigateToOpenRouterSettings: () -> Unit = {},
    onNavigateToProviderSettings: (String) -> Unit = {},
    onNavigateToPlatformSetting: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit = {},
    onDelegation: () -> Unit = {},
    onWorkspace: (String) -> Unit = {},
    onActivity: () -> Unit = {},
    onPreferences: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allPlatforms by settingViewModel.platformState.collectAsStateWithLifecycle()
    val connections by settingViewModel.providerConnections.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf("All") }
    val platforms = remember(allPlatforms, query, favoritesOnly, source) {
        allPlatforms.filter { profile ->
            val kind = when (profile.compatibleType) {
                ClientType.LITERT_LM -> "Local"
                ClientType.FREE -> "Free"
                else -> "Remote"
            }
            dev.chungjungsoo.gptmobile.presentation.common.profileMatchesQuery(profile, query) &&
                (!favoritesOnly || profile.isFavorite) &&
                (source == "All" || source == kind)
        }.sortedWith(compareByDescending<PlatformV2> { it.isFavorite }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
    }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        androidx.compose.material3.LargeTopAppBar(title = { Text("AI") }, actions = {
            IconButton(onClick = onActivity) { Icon(Icons.Rounded.Timeline, "Activity") }
        })
    }, floatingActionButton = {
        androidx.compose.material3.ExtendedFloatingActionButton(onClick = onNavigateToAddPlatform, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New profile") })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SettingsTabs(listOf("Profiles", "Connections", "Local"), tab) { if (it == 2) onNavigateToLocalModels() else tab = it } }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppSearchField(query, { query = it }, if (tab == 0) "Find an AI profile" else "Find a connection") }
            if (tab == 0) {
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Remote", "Local", "Free").forEach { label -> androidx.compose.material3.FilterChip(source == label, { source = label }, label = { Text(label) }) }
                        androidx.compose.material3.FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("Favourites") })
                    }
                }
                if (platforms.isEmpty()) item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppEmptyState(if (query.isBlank()) "No matching profiles" else "No profiles match your search", "Add profile", onNavigateToAddPlatform) }
                items(platforms, key = { it.uid }) { profile ->
                    PlatformItemCard(profile, { settingViewModel.togglePlatformFavorite(profile.id) }, { onNavigateToPlatformSetting(profile.uid) })
                }
            } else {
                val visible = connections.filter { query.isBlank() || it.name.contains(query, true) || it.compatibleType.name.contains(query, true) }
                if (visible.isEmpty()) item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppEmptyState("No matching connections", "Add connection", onNavigateToAddPlatform) }
                items(visible, key = { "connection:${it.uid}" }) { connection ->
                    dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow(connection.name, "${connection.compatibleType.name} · ${allPlatforms.count { it.providerConnectionUid == connection.uid }} profiles", Icons.Rounded.Cloud, { onNavigateToProviderSettings(connection.uid) })
                    if (connection.compatibleType == ClientType.OPENROUTER) {
                        TextButton(onClick = onNavigateToOpenRouterSettings) { Text("OpenRouter options") }
                        TextButton(onClick = { onPreferences("OPENROUTER") }) { Text("Batch processing policy") }
                    }
                }
            }
            if (tab == 1) item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow("Provider discovery", "Automatic model catalogue discovery", Icons.Rounded.Tune, { onPreferences("CONNECTIONS") }) }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppSectionTitle("Configure your AI", Modifier.padding(top = 16.dp)) }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow("Delegation", "Helpers, reviewer, research depth and limits", Icons.Rounded.AutoAwesome, onDelegation) }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow("Recipes", "Reusable task instructions", Icons.Rounded.Description, { onWorkspace("Recipes") }) }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow("Compare models", "Existing measurements and model comparison", Icons.Rounded.CompareArrows, { onWorkspace("Models") }) }
            item { dev.chungjungsoo.gptmobile.presentation.designsystem.AppDestinationRow("Budgets", "Spend and token limits", Icons.Rounded.Tune, { onWorkspace("Budgets") }) }
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

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            .combinedClickable(
                onClick = onEdit,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFavorite()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                Text(if (platform.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (labelsList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        labelsList.forEach { label ->
                            BeveledProfileLabel(label = label)
                        }
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) { Icon(if (platform.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (platform.isFavorite) "Remove favourite" else "Add favourite") }
        }
    }
}
