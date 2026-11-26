package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.agent.GitHubToolInventory
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardTarget
import dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GitHubWorkspaceInventory(
    state: GitHubProgrammerState,
    target: GatewayDashboardTarget?,
    privateWorkspace: Boolean,
    onDismiss: () -> Unit,
    model: GitHubGatewayStatusViewModel = hiltViewModel()
) {
    val observations by GitHubToolInventory.observations.collectAsStateWithLifecycle()
    val admitted = remember(state.chatId, state.context, state.runs, observations) {
        latestGitHubAdmission(state.chatId, state.context, state.runs, observations)
    }
    var inventory by remember(target?.identity) { mutableStateOf<GatewayCapabilityInventory?>(null) }
    var refresh by remember(target?.identity) { mutableIntStateOf(0) }
    LaunchedEffect(target?.identity, refresh) {
        if (refresh > 0 && target?.config != null) {
            val previous = inventory
            inventory = GatewayCapabilityInventory(target.identity, previous?.observation, loading = true)
            inventory = model.readInventory(target, previous)
        }
    }
    GitHubToolInventorySheet(
        inventory = inventory,
        gatewayLabel = target?.let { listOf(it.name, it.host).filter(String::isNotBlank).joinToString(" · ") },
        gatewayMessage = if (privateWorkspace) "Gateway discovery is unavailable in this private workspace." else target?.problem ?: if (target == null) "Choose an engineering Gateway in GitHub settings to discover its tools." else null,
        onRefreshGateway = if (target?.config != null) {
            { refresh++ }
        } else {
            null
        },
        observation = admitted,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubToolInventorySheet(
    inventory: GatewayCapabilityInventory?,
    gatewayLabel: String?,
    gatewayMessage: String?,
    onRefreshGateway: (() -> Unit)?,
    onDismiss: () -> Unit,
    observation: GitHubToolInventory.Observation? = null,
    initialSection: GitHubInventorySection = GitHubInventorySection.BUILT_IN,
    initialCategory: String? = null,
    includeRun: Boolean = true
) {
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val catalog by produceState<List<GitHubInventoryRow>?>(null) { value = withContext(Dispatchers.Default) { builtInGitHubInventory() } }
    val snapshot = inventory?.observation
    val rows = remember(catalog, section, snapshot, observation) {
        when (section) {
            GitHubInventorySection.GATEWAY -> snapshot?.let(::gatewayInventoryRows).orEmpty()
            GitHubInventorySection.RUN -> mountedGitHubInventory(observation)
            else -> catalog.orEmpty()
        }
    }
    val results = remember(rows, section, query, category) { gitHubInventoryResults(rows, section, query, category) }
    val list = rememberLazyListState()
    LaunchedEffect(section, query, category) { list.scrollToItem(0) }
    FadingModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tools and features", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("github_inventory_search"), singleLine = true, label = { Text("Search tools and features") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val sources = listOfNotNull(GitHubInventorySection.BUILT_IN, GitHubInventorySection.RUN.takeIf { includeRun }, GitHubInventorySection.GATEWAY)
                sources.forEach { option ->
                    FilterChip(section == option || option == GitHubInventorySection.BUILT_IN && section.builtIn, {
                        section = option
                        category = null
                        expanded = null
                        if (option == GitHubInventorySection.GATEWAY && inventory == null) onRefreshGateway?.invoke()
                    }, label = { Text(if (option == GitHubInventorySection.BUILT_IN) "Built-in catalog" else option.label) })
                }
            }
            if (section == GitHubInventorySection.BUILT_IN || section.builtIn) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GitHubInventorySection.entries.filter { it == GitHubInventorySection.BUILT_IN || it.builtIn }.forEach { option ->
                        FilterChip(section == option, { section = option }, label = { Text(if (option == GitHubInventorySection.BUILT_IN) "All" else option.label) })
                    }
                }
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    when (section) {
                        GitHubInventorySection.GATEWAY -> Column {
                            gatewayLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                            snapshot?.let {
                                Text("${it.status.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)} inventory · ${it.tools.size} tools · ${inventoryDate(it.observedAtMillis)}", style = MaterialTheme.typography.bodySmall)
                                it.reason?.let { reason -> Text(reason, style = MaterialTheme.typography.bodySmall) }
                                if (it.hasMore) Text("More tools exist beyond this observation.", style = MaterialTheme.typography.bodySmall)
                                if (inventory.loading || inventory.error != null) Text("Showing the previous observation.", style = MaterialTheme.typography.bodySmall)
                            }
                            (inventory?.error ?: gatewayMessage)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            onRefreshGateway?.let { refresh ->
                                TextButton(onClick = refresh, enabled = inventory?.loading != true) { Text(if (inventory?.loading == true) "Checking Gateway…" else "Refresh inventory") }
                            }
                            category?.let { TextButton(onClick = { category = null }) { Text("Clear $it filter") } }
                        }
                        GitHubInventorySection.RUN -> Text(
                            observation?.let { "Tools authorized for this chat’s latest run · ${inventoryDate(it.observedAtMillis)}" }
                                ?: "No tool admission record is available for this chat’s latest run.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> Text("Included in the app. Access depends on the profile, connection and task.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (query.isNotBlank() && results.isNotEmpty()) {
                    item { Text(if (results.size == 1) "1 match" else "${results.size} matches", style = MaterialTheme.typography.labelMedium) }
                }
                if (results.isEmpty()) {
                    item {
                        val message = when {
                            section == GitHubInventorySection.GATEWAY && snapshot == null -> null
                            section == GitHubInventorySection.RUN && observation == null -> null
                            section !in setOf(GitHubInventorySection.RUN, GitHubInventorySection.GATEWAY) && catalog == null -> "Loading catalog…"
                            query.isNotBlank() -> "No matches for “${query.trim()}”."
                            else -> "No tools recorded."
                        }
                        message?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                items(results, key = { it.key }) { row ->
                    val isExpanded = expanded == row.key
                    val action = if (row.details.isEmpty()) {
                        Modifier
                    } else {
                        Modifier.clickable { expanded = if (isExpanded) null else row.key }
                            .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" }
                    }
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(action.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                if (row.details.isNotEmpty()) ThemeIcon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                            }
                            Text(row.subtitle, style = MaterialTheme.typography.bodySmall)
                            if (isExpanded) row.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

private fun inventoryDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
