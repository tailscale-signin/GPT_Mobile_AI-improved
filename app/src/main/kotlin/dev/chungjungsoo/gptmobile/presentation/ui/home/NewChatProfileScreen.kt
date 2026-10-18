package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.collectReusableProfileLabels
import dev.chungjungsoo.gptmobile.data.model.parseProfileLabels
import dev.chungjungsoo.gptmobile.data.model.researchStartBlockReason
import dev.chungjungsoo.gptmobile.data.pricing.decimalRate
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ProfileLabelChip
import dev.chungjungsoo.gptmobile.presentation.common.ProfileSearchField
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.profileMatchesQuery

private enum class ProfileFilter(val title: String) {
    ALL("All"),
    FAVORITES("Favorites"),
    LOCAL("Local"),
    REMOTE("Remote"),
    RESEARCH("Research-ready")
}
private enum class ProfileSort(val title: String) {
    FAVORITES("Favorites first"),
    NAME("Name A–Z"),
    PROVIDER("Provider"),
    MODEL("Model"),
    BENCHMARK("Benchmark score"),
    PRICE("Listed input price · USD")
}

private fun isLocalOrSelfHosted(profile: PlatformV2): Boolean =
    profile.compatibleType.name in setOf("LITERT_LM", "LLAMA", "OLLAMA")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatProfileScreen(
    viewModel: NewChatViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onManageTools: () -> Unit,
    insightViewModel: NewChatInsightsViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val features by viewModel.features.collectAsStateWithLifecycle()
    val selected by viewModel.selectedUids.collectAsStateWithLifecycle()
    val primary by viewModel.primaryUid.collectAsStateWithLifecycle()
    val researchDepth by viewModel.researchDepth.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val approvingResearch by viewModel.approvingResearch.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val insights by insightViewModel.insights.collectAsStateWithLifecycle()
    val kind = viewModel.type ?: NewChatType.SINGLE
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ProfileFilter.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(ProfileSort.FAVORITES.name) }
    var labelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    var primaryOpen by rememberSaveable { mutableStateOf(false) }
    var researchExpanded by rememberSaveable { mutableStateOf(false) }
    var detailsUid by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = ProfileFilter.entries.firstOrNull { it.name == filterName } ?: ProfileFilter.ALL
    val sort = ProfileSort.entries.firstOrNull { it.name == sortName } ?: ProfileSort.FAVORITES
    val labels = remember(profiles) { collectReusableProfileLabels(profiles.map { it.labels }) }
    val availableUids = remember(profiles) { profiles.filter { it.enabled }.mapTo(mutableSetOf()) { it.uid } }
    val currentSelection = remember(selected, availableUids) { selected.filter { it in availableUids } }
    val researchPolicy = remember(researchDepth) {
        MultiSearchResearchSelection(researchToggle = true, depth = researchDepth, scope = ResearchContentScope.WEB_ONLY)
    }
    val baseOrder = remember(profiles, sort, insights) {
        when (sort) {
            ProfileSort.FAVORITES -> orderedChatProfiles(profiles).map { it.second }
            ProfileSort.NAME -> profiles.filter { it.enabled }.sortedBy { it.name.lowercase() }
            ProfileSort.PROVIDER -> profiles.filter { it.enabled }.sortedWith(compareBy({ it.compatibleType.name }, { it.name.lowercase() }))
            ProfileSort.MODEL -> profiles.filter { it.enabled }.sortedWith(compareBy({ it.model.lowercase() }, { it.name.lowercase() }))
            ProfileSort.BENCHMARK -> profiles.filter { it.enabled }.sortedWith(
                compareBy<PlatformV2> { insights[it.uid]?.score == null }
                    .thenByDescending { insights[it.uid]?.score?.score?.toDouble() ?: -1.0 }
                    .thenBy { it.name.lowercase() }
            )
            ProfileSort.PRICE -> profiles.filter { it.enabled }.sortedWith(
                compareBy<PlatformV2> { insights[it.uid]?.price?.takeIf { price -> price.currency == "USD" } == null }
                    .thenBy { decimalRate(insights[it.uid]?.price?.takeIf { price -> price.currency == "USD" }?.inputPerMillion) }
                    .thenBy { it.name.lowercase() }
            )
        }
    }
    val visible = remember(baseOrder, query, labelKey, filter, features, researchPolicy) {
        baseOrder.filter { profile ->
            val matchingType = when (filter) {
                ProfileFilter.ALL -> true
                ProfileFilter.FAVORITES -> profile.isFavorite
                ProfileFilter.LOCAL -> isLocalOrSelfHosted(profile)
                ProfileFilter.REMOTE -> !isLocalOrSelfHosted(profile)
                ProfileFilter.RESEARCH -> researchStartBlockReason(
                    researchPolicy,
                    features,
                    profile.uid,
                    !profile.disableAllTools && !profile.disableRemoteTools
                ) == null
            }
            matchingType &&
                profileMatchesQuery(profile, query) &&
                (labelKey == null || parseProfileLabels(profile.labels).any { it.key == labelKey })
        }
    }
    val blockers = viewModel.researchBlockers()
    val combined = kind.executionMode(currentSelection.size) == dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.COMBINED
    val canStart = kind.isCountValid(currentSelection.size) && !creating && (kind != NewChatType.RESEARCH || blockers.isEmpty())
    val selectedNames = currentSelection.mapNotNull { uid -> profiles.firstOrNull { it.uid == uid }?.name }
    val selectedModeLabel = when {
        combined -> "Combined"
        kind == NewChatType.MULTI_CHAT -> "Independent"
        else -> "Single"
    }
    val hiddenCount = currentSelection.count { uid -> visible.none { it.uid == uid } }
    val details = detailsUid?.let { uid -> insights[uid]?.takeIf { it.profile.uid == uid } }
    LaunchedEffect(insightViewModel) { insightViewModel.refreshWhileVisible() }
    LaunchedEffect(labels) {
        if (labelKey != null && labels.none { it.key == labelKey }) labelKey = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("Select AI Profiles") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text("STEP 2 OF 2 · " + kind.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (kind == NewChatType.RESEARCH) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Deep Research · On by default", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Web research · Depth " + researchDepth + "% · " + if (combined) "Combined" else "Combined when 2+ are selected",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { researchExpanded = !researchExpanded }) {
                                    Text(if (researchExpanded) "Hide research settings" else "Adjust research depth")
                                }
                                if (researchExpanded) {
                                    Slider(
                                        value = researchDepth.toFloat(),
                                        onValueChange = { viewModel.setResearchDepth(it.toInt()) },
                                        valueRange = 1f..100f,
                                        steps = 98
                                    )
                                    Text("Web only until Papers & Studies are explicitly permitted.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        Text(
                            when (kind) {
                                NewChatType.SINGLE -> "Select exactly one profile."
                                NewChatType.MULTI_CHAT -> "Select two or more profiles for independent chats."
                                NewChatType.COMBINED -> "Select two or more models for a comprehensive answer."
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ProfileSearchField(query, { query = it }, Modifier.testTag("new_chat_search"))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProfileFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filterName = option.name },
                                label = { Text(option.title) }
                            )
                        }
                    }
                    if (labels.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            labels.forEach { label ->
                                ProfileLabelChip(label, labelKey == label.key, { labelKey = if (labelKey == label.key) null else label.key })
                            }
                            TextButton(onClick = { labelKey = null }) { Text("Clear labels") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(visible.size.toString() + " profiles", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Column {
                            TextButton(onClick = { sortOpen = true }) {
                                Icon(Icons.Rounded.Sort, null)
                                Text(sort.title, Modifier.padding(start = 6.dp))
                            }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                ProfileSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.title) },
                                        onClick = {
                                            sortName = option.name
                                            sortOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(currentSelection.size.toString() + " selected · " + selectedModeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (selectedNames.isNotEmpty()) {
                        Text(selectedNames.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    if (hiddenCount > 0) Text(hiddenCount.toString() + " selected profile(s) hidden by filters", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    if (combined && currentSelection.size >= 2) {
                        Column {
                            TextButton(onClick = { primaryOpen = true }) {
                                Text("Synthesis lead: " + (profiles.firstOrNull { it.uid == primary }?.name ?: selectedNames.firstOrNull().orEmpty()))
                            }
                            DropdownMenu(expanded = primaryOpen, onDismissRequest = { primaryOpen = false }) {
                                currentSelection.forEach { uid ->
                                    val name = profiles.firstOrNull { it.uid == uid }?.name ?: uid
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            viewModel.setPrimary(uid)
                                            primaryOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (kind == NewChatType.RESEARCH && blockers.isNotEmpty()) {
                        Text(blockers.joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::grantWebResearchAccess, enabled = !approvingResearch) { Text("Enable Research plugin globally") }
                            TextButton(onClick = onManageTools) { Text("Tools & Permissions") }
                        }
                    }
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (profiles.none { it.enabled }) {
                        TextButton(onClick = onManageProfiles) { Text("Add or enable an AI profile") }
                    }
                    Button(
                        onClick = { viewModel.start(onCreated) },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth().testTag("new_chat_start")
                    ) {
                        Text(if (creating) "Creating…" else "Start Chat")
                    }
                }
            }
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).testTag("new_chat_profiles_screen"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (visible.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (profiles.isEmpty()) "No AI profiles are configured." else "No profiles match these filters.")
                        TextButton(onClick = {
                            filterName = ProfileFilter.ALL.name
                            labelKey = null
                            query = ""
                        }) { Text("Clear filters") }
                        TextButton(onClick = onManageProfiles) { Text("Manage AI Profiles") }
                    }
                }
            }
            items(visible, key = { it.uid }) { profile ->
                SelectPlatformProfileCard(
                    profile = profile,
                    selected = profile.uid in currentSelection,
                    insight = insights[profile.uid]?.takeIf { it.matches(profile) },
                    onToggle = viewModel::toggleProfile,
                    onFavorite = { viewModel.toggleFavorite(profile.uid) },
                    onInsights = { detailsUid = profile.uid }
                )
                Text(
                    if (isLocalOrSelfHosted(profile)) "Local / self-hosted" else "Remote provider",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    details?.let { insight ->
        ProfileInsightDetailsDialog(insight, insightViewModel::refreshNow, { viewModel.toggleFavorite(insight.profile.uid) }, { detailsUid = null })
    }
}
