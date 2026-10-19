package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeConfiguration
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.model.settingsHomeRows
import dev.chungjungsoo.gptmobile.data.openrouter.account.SettingsHomeWalletState
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun SettingsHomePanel(points: List<SettingsHomePoint>, onClick: (() -> Unit)?, modifier: Modifier = Modifier, onCategoryClick: ((String) -> Unit)? = null) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("settings_home_bubble").then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Configure Home tab", onClick = onClick)),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.03f)))).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Home tab", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Your selected information", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (onClick == null) Icons.Rounded.Tune else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (points.isEmpty()) {
                Text(if (onClick == null) "Choose up to five items from the list below." else "Choose up to five items to show here. Tap to configure Home tab.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("settings_home_empty"))
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.4f) 2 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        settingsHomeRows(points.size, columns).forEach { range ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                range.forEach { index ->
                                    val point = points[index]
                                    val action = onCategoryClick?.takeIf { point.id in setOf(SettingsHomeConfiguration.PROFILES, SettingsHomeConfiguration.TOOLS) }
                                    HomePoint(point, Modifier.weight(1f).then(if (action == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Show all ${point.title}") { action(point.id) }))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSubjectsDialog(point: SettingsHomePoint, onDismiss: () -> Unit, onConfigure: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(point.title) },
        text = {
            if (point.subjects.isEmpty()) {
                Text(point.detail)
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(point.subjects, key = { it.id }) { HomePoint(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfigure) { Text("Choose items") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun HomePoint(point: SettingsHomePoint, modifier: Modifier = Modifier) {
    val statusColor = homeStatusColor(point.tone)
    Column(modifier.testTag("settings_home_point_${point.id}").semantics(mergeDescendants = true) { stateDescription = point.value + point.secondaryLabel?.let { " · $it" }.orEmpty() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(point.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(statusColor, CircleShape))
            Text(point.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = statusColor)
        }
        point.secondaryLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = homeStatusColor(point.secondaryTone))
        }
        Text(point.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        point.subjects.take(3).forEach { subject ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(homeStatusColor(subject.tone), CircleShape))
                Text("${subject.title}: ${subject.value}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (point.subjects.size > 3) Text("${point.subjects.size - 3} more", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun homeStatusColor(tone: SettingsHomeTone): Color = when (tone) {
    SettingsHomeTone.POSITIVE -> if (MaterialTheme.colorScheme.surfaceContainerLow.luminance() < 0.5f) Color(0xFF74DBA2) else Color(0xFF176B3A)
    SettingsHomeTone.NEGATIVE -> MaterialTheme.colorScheme.error
    SettingsHomeTone.ACTIVE -> MaterialTheme.colorScheme.primary
    SettingsHomeTone.STALE -> if (MaterialTheme.colorScheme.surfaceContainerLow.luminance() < 0.5f) Color(0xFFFFCC80) else Color(0xFF7A4B00)
    SettingsHomeTone.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHomeScreen(
    configuration: SettingsHomeConfiguration,
    points: List<SettingsHomePoint>,
    profiles: List<PlatformV2>,
    providerConnections: List<ProviderConnection>,
    wallet: SettingsHomeWalletState,
    saveError: String?,
    onSetItem: (String, Boolean) -> Unit,
    onSelectWalletConnection: (String) -> Unit,
    onHideWalletAmounts: (Boolean) -> Unit,
    onOpenRouter: () -> Unit,
    onBack: () -> Unit,
    onSelectProfiles: (List<String>) -> Unit,
    toolGroups: List<dev.chungjungsoo.gptmobile.data.home.HomeToolGroup>,
    onSelectToolSubjects: (List<dev.chungjungsoo.gptmobile.data.model.HomeToolSubject>) -> Unit
) {
    val selected = configuration.migrate(profiles.filter { it.enabled }.map { it.uid })
    var showProfiles by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    val options = listOf(
        HomeOption(SettingsHomeConfiguration.WALLET, "OpenRouter Wallet", "Account balance from the authenticated credits API"),
        HomeOption(SettingsHomeConfiguration.PROFILES, "AI Profiles", "Selected profiles and their recorded status"),
        HomeOption(SettingsHomeConfiguration.USAGE, "Recent activity", "Completed and failed replies in the latest 250 records"),
        HomeOption(SettingsHomeConfiguration.TOOLS, "Plugins & Tools", "Configured connections and global remote tool setting"),
        HomeOption(SettingsHomeConfiguration.PRIVACY, "Privacy choices", "Diagnostics, Local Assist and location tool settings"),
        HomeOption(SettingsHomeConfiguration.BACKUP, "Backup status", "Most recent backup recorded by the app")
    )
    val connection = homeWalletConnection(selected, providerConnections)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Home tab") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Settings") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SettingsHomePanel(points, onClick = null) }
            item {
                Text("Show in Settings", style = MaterialTheme.typography.titleMedium)
                Text("${selected.selectedItems.size} of ${SettingsHomeConfiguration.MAX_ITEMS} categories selected. Changes save immediately. Turn one off to make room for another.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("home_selection_count"))
                Text("A green Enabled or red Disabled indicator shows each selected profile’s switch. Its request result is separate, with unknown and stale states; no background test requests are sent.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("home_save_error")) }
            }
            items(options, key = { it.id }) { option ->
                HomeOptionRow(option, option.id in selected.selectedItems, selected.selectedItems.size < SettingsHomeConfiguration.MAX_ITEMS || option.id in selected.selectedItems) { onSetItem(option.id, it) }
            }
            if (SettingsHomeConfiguration.PROFILES in selected.selectedItems) {
                item {
                    TextButton(onClick = { showProfiles = true }) { Text("Choose profiles (${selected.selectedProfileUids.size})") }
                    points.firstOrNull { it.id == SettingsHomeConfiguration.PROFILES }?.subjects?.forEach { HomePoint(it) }
                }
            }
            if (SettingsHomeConfiguration.TOOLS in selected.selectedItems) {
                item {
                    TextButton(onClick = { showTools = true }) { Text("Choose plugins and tools (${selected.selectedToolSubjects.size})") }
                    points.firstOrNull { it.id == SettingsHomeConfiguration.TOOLS }?.subjects?.forEach { HomePoint(it) }
                }
            }
            if (SettingsHomeConfiguration.WALLET in selected.selectedItems) {
                item {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Text("OpenRouter Wallet", style = MaterialTheme.typography.titleMedium)
                        Text("The cached account balance uses management access saved in OpenRouter settings. Each eligible remote response can update it while Wallet is enabled.", style = MaterialTheme.typography.bodyMedium)
                        providerConnections.filter { it.compatibleType == ClientType.OPENROUTER }.forEach { candidate ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onSelectWalletConnection(candidate.uid) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(candidate.uid == connection?.uid, onClick = { onSelectWalletConnection(candidate.uid) })
                                Text(candidate.name, Modifier.weight(1f))
                            }
                        }
                        if (connection == null) Text("Select or add an OpenRouter connection to read its account balance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HomeOptionRow(HomeOption("hide_wallet_amounts", "Hide wallet amounts", "Keep the amount hidden in the Settings bubble"), selected.hideWalletAmounts, true, onHideWalletAmounts)
                        if (wallet.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        wallet.failure?.let { Text(walletFailureText(it), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = onOpenRouter) { Text("Manage OpenRouter") }
                        Text(if (wallet.associationValid) "The next OpenRouter response can update this balance. Opening Home does not make an account request." else "Save or reassociate management access in provider settings to receive wallet updates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showTools) {
        HomeToolSelectionDialog(toolGroups, selected.selectedToolSubjects, onDismiss = { showTools = false }) {
            onSelectToolSubjects(it)
            showTools = false
        }
    }
    if (showProfiles) {
        HomeProfileSelectionDialog(profiles, selected.selectedProfileUids, onDismiss = { showProfiles = false }) {
            onSelectProfiles(it)
            showProfiles = false
        }
    }
}

@Composable
private fun HomeProfileSelectionDialog(
    profiles: List<PlatformV2>,
    selected: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit
) {
    var draft by remember { mutableStateOf(selected.toSet()) }
    var query by remember { mutableStateOf("") }
    val known = profiles.associateBy { it.uid }
    val choices = (profiles.map { it.uid } + selected).distinct().filter { uid ->
        val profile = known[uid]
        query.isBlank() || listOf(profile?.name.orEmpty(), profile?.model.orEmpty(), profile?.compatibleType?.name.orEmpty(), uid).any { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose profiles") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search profiles") }, singleLine = true)
                Row {
                    TextButton(onClick = { draft = draft + profiles.map { it.uid } }) { Text("Select all") }
                    TextButton(onClick = { draft = emptySet() }) { Text("Clear") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it }) { uid ->
                        val profile = known[uid]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(uid in draft, role = Role.Checkbox) {
                                draft = if (it) draft + uid else draft - uid
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(uid in draft, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(profile?.name ?: "Unavailable profile")
                                Text(profile?.let { "${it.model} · ${it.compatibleType}" } ?: uid, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft.toList()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private data class HomeOption(val id: String, val title: String, val detail: String)

@Composable
private fun HomeOptionRow(option: HomeOption, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("home_toggle_${option.id}").toggleable(value = selected, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = selected, onCheckedChange = null, enabled = enabled)
    }
}
