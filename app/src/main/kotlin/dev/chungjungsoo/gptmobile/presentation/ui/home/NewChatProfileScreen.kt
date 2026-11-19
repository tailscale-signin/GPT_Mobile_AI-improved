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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.NewChatType
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.collectReusableProfileLabels
import dev.chungjungsoo.gptmobile.data.model.parseProfileLabels
import dev.chungjungsoo.gptmobile.data.model.researchStartBlockReason
import dev.chungjungsoo.gptmobile.data.pricing.decimalRate
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyProfileEligibility
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ProfileLabelChip
import dev.chungjungsoo.gptmobile.presentation.common.ProfileSearchField
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.profileMatchesQuery
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayModelPreparationContent
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PrivacyModePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

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
    val privacy by viewModel.privacyState.collectAsStateWithLifecycle()
    val privacyChecking by viewModel.privacyChecking.collectAsStateWithLifecycle()
    val selected by viewModel.selectedUids.collectAsStateWithLifecycle()
    val modelPreparation by viewModel.gatewayModelPreparation.collectAsStateWithLifecycle()
    val primary by viewModel.primaryUid.collectAsStateWithLifecycle()
    val researchDepth by viewModel.researchDepth.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val approvingResearch by viewModel.approvingResearch.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val insights by insightViewModel.insights.collectAsStateWithLifecycle()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val gatewayObservations = remember(viewModel, windowFocused) { if (windowFocused) viewModel.gatewayAvailability else flowOf(emptyMap()) }
    val gatewayAvailability by gatewayObservations.collectAsStateWithLifecycle(initialValue = emptyMap(), minActiveState = Lifecycle.State.RESUMED)
    val listState = rememberLazyListState()
    val visibleKeys by remember(listState) { derivedStateOf { listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key } } }
    val accessibilityInteracting by rememberGatewayAccessibilityInteraction()
    var pointerDown by remember { mutableStateOf(false) }
    var focusedUid by remember { mutableStateOf<String?>(null) }
    var waitingForIdle by remember { mutableStateOf(false) }
    val interactionActive = pointerDown || listState.isScrollInProgress || focusedUid != null
    LaunchedEffect(interactionActive) {
        if (interactionActive) {
            waitingForIdle = true
        } else {
            delay(250)
            waitingForIdle = false
        }
    }
    val kind = viewModel.type ?: NewChatType.SINGLE
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ProfileFilter.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(ProfileSort.FAVORITES.name) }
    var labelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    var primaryOpen by rememberSaveable { mutableStateOf(false) }
    var researchExpanded by rememberSaveable { mutableStateOf(false) }
    var detailsUid by rememberSaveable { mutableStateOf<String?>(null) }
    var privacySettingsOpen by rememberSaveable { mutableStateOf(false) }
    var privacyRefresh by remember { mutableIntStateOf(0) }
    val filter = ProfileFilter.entries.firstOrNull { it.name == filterName } ?: ProfileFilter.ALL
    val sort = ProfileSort.entries.firstOrNull { it.name == sortName } ?: ProfileSort.FAVORITES
    val labels = remember(profiles) { collectReusableProfileLabels(profiles.map { it.labels }) }
    val checkedProfiles by viewModel.checkedProfiles.collectAsStateWithLifecycle()
    LaunchedEffect(profiles, privacy, windowFocused, privacyRefresh, privacySettingsOpen) {
        if (windowFocused && !privacySettingsOpen) {
            do {
                viewModel.checkPrivacyProfiles(profiles)
                if (privacy.enabled) delay(60_000)
            } while (privacy.enabled)
        }
    }
    val privacyDecisions = remember(profiles, privacy, checkedProfiles) {
        profiles.filter { it.enabled }.associate { it.uid to viewModel.privacyDecision(it) }
    }
    val availableUids = remember(privacyDecisions) {
        privacyDecisions.filterValues { it.eligible }.keys
    }
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
    val matching = remember(baseOrder, query, labelKey, filter, features, researchPolicy) {
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
    val onlineUids = remember(gatewayAvailability) { gatewayAvailability.filterValues { it.isOnline }.keys }
    val desiredOrder = remember(matching, onlineUids) { onlineFirstChatProfiles(matching, onlineUids) }
    var displayedUids by remember(query, labelKey, filter, sort) { mutableStateOf(emptyList<String>()) }
    val visible = deferredChatProfileOrder(desiredOrder, displayedUids, interactionActive || waitingForIdle || accessibilityInteracting)
    SideEffect {
        val next = visible.map { it.uid }
        if (next != displayedUids) displayedUids = next
        if (focusedUid != null && matching.none { it.uid == focusedUid }) focusedUid = null
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

    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(conversationTypeIcon(kind), null, tint = MaterialTheme.colorScheme.primary)
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_choose_ai))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Privacy Mode", style = MaterialTheme.typography.labelSmall)
                            Checkbox(
                                checked = privacy.enabled,
                                onCheckedChange = viewModel::setPrivacyMode,
                                modifier = Modifier.testTag("new_chat_privacy_mode")
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (privacy.enabled) {
                        Text("Privacy-Verified AI Profiles", style = MaterialTheme.typography.titleSmall)
                        val counts = privacyDecisions.values.groupingBy { it.status }.eachCount()
                        Text(
                            listOf(
                                "${counts[PrivacyProfileEligibility.Status.AVAILABLE] ?: 0} available",
                                "${counts[PrivacyProfileEligibility.Status.CHECKING] ?: 0} checking",
                                "${counts[PrivacyProfileEligibility.Status.NOT_VERIFIED] ?: 0} not verified",
                                "${counts[PrivacyProfileEligibility.Status.INCOMPATIBLE] ?: 0} incompatible"
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("privacy_profile_counts")
                        )
                        Text("Unavailable profiles stay saved. Missing verification does not mean a provider retains your data.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { privacyRefresh++ }, enabled = !privacyChecking, modifier = Modifier.testTag("privacy_profiles_recheck")) {
                                Text(if (privacyChecking) "Checking profiles…" else "Recheck profiles")
                            }
                            TextButton(onClick = { privacySettingsOpen = true }, modifier = Modifier.testTag("privacy_profile_settings")) { Text("Privacy settings") }
                        }
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
                    TextButton(onClick = { optionsOpen = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_options)) }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_selected, currentSelection.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (hiddenCount > 0) Text(hiddenCount.toString() + " selected profile(s) hidden by filters", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
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
            modifier = Modifier.fillMaxSize().padding(insets).testTag("new_chat_profiles_screen").pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) pointerDown = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                    }
                } finally {
                    pointerDown = false
                }
            },
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (kind == NewChatType.SINGLE || kind == NewChatType.RESEARCH) {
                item(key = "profile_recommendations") {
                    Column {
                        Text("Choose by task", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            ChatRouteChoice.entries.forEach { choice ->
                                val research = kind == NewChatType.RESEARCH || choice == ChatRouteChoice.RESEARCH
                                val target = remember(choice, research, visible, availableUids, insights, gatewayAvailability, features, researchPolicy) {
                                    recommendedChatProfile(choice, visible.filter { it.uid in availableUids }, insights, gatewayAvailability, research) { profile ->
                                        features.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH) &&
                                            researchStartBlockReason(researchPolicy, features, profile.uid, !profile.disableAllTools && !profile.disableRemoteTools) == null
                                    }
                                }
                                TextButton(onClick = {
                                    target?.let { viewModel.chooseSuggestedProfile(it.uid, research) }
                                }, enabled = target != null && !creating) { Text(choice.label) }
                            }
                        }
                    }
                }
            }
            if (accessibilityInteracting) {
                item(key = "gateway_profile_order") {
                    TextButton(
                        onClick = { displayedUids = desiredOrder.map { it.uid } },
                        enabled = desiredOrder.map { it.uid } != displayedUids
                    ) { Text(stringResource(R.string.gateway_availability_reorder)) }
                }
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectPlatformProfileCard(
                        profile = profile,
                        privacyRestriction = viewModel.privacyDecision(profile).takeUnless { it.eligible }?.explanation,
                        privacyStatus = privacyDecisions[profile.uid]?.status.takeIf { privacy.enabled },
                        selected = profile.uid in currentSelection,
                        insight = insights[profile.uid]?.takeIf { it.matches(profile) },
                        onToggle = viewModel::toggleProfile,
                        onFavorite = { viewModel.toggleFavorite(profile.uid) },
                        onInsights = { detailsUid = profile.uid },
                        gatewayAvailability = gatewayAvailability[profile.uid],
                        animateGateway = profile.uid in visibleKeys && !optionsOpen && !sortOpen && !primaryOpen && !privacySettingsOpen && detailsUid == null,
                        onFocus = { focused ->
                            if (focused) {
                                focusedUid = profile.uid
                            } else if (focusedUid == profile.uid) {
                                focusedUid = null
                            }
                        }
                    )
                    if (privacy.enabled) {
                        val decision = viewModel.privacyDecision(profile)
                        Text(decision.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("privacy_profile_reason_${profile.uid}"))
                        decision.policyUrl?.let { url ->
                            val handler = androidx.compose.ui.platform.LocalUriHandler.current
                            TextButton(onClick = { handler.openUri(url) }) { Text("Provider privacy terms") }
                        }
                    }
                    modelPreparation[profile.uid]?.takeIf { profile.uid in currentSelection }?.let { state ->
                        GatewayModelPreparationContent(state) { viewModel.recheckGatewayModel(profile.uid) }
                    }
                }
            }
        }
    }
    if (privacySettingsOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet(onDismissRequest = { privacySettingsOpen = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                TextButton(onClick = { privacySettingsOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                PrivacyModePanel()
            }
        }
    }
    if (optionsOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingModalBottomSheet(onDismissRequest = { optionsOpen = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == NewChatType.RESEARCH) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_research_depth), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
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
                            NewChatType.SINGLE, NewChatType.GITHUB -> "Select exactly one profile."
                            NewChatType.MULTI_CHAT -> "Select two or more profiles for independent chats."
                            NewChatType.COMBINED -> "Select two or more models for a comprehensive answer."
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                if (combined && currentSelection.size >= 2) {
                    Column {
                        TextButton(onClick = { primaryOpen = true }) {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.audit_final_writer) + ": " + (profiles.firstOrNull { it.uid == primary }?.name ?: selectedNames.firstOrNull().orEmpty()))
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
            }
        }
    }
    details?.let { insight ->
        ProfileInsightDetailsDialog(insight, insightViewModel::refreshNow, { viewModel.toggleFavorite(insight.profile.uid) }, { detailsUid = null })
    }
}
