package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterMaxPrice
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterPolicy
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.data.openrouter.account.AccountFailure
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterTab
import dev.chungjungsoo.gptmobile.data.openrouter.account.money
import dev.chungjungsoo.gptmobile.data.openrouter.account.perMillion
import dev.chungjungsoo.gptmobile.data.openrouter.account.text
import dev.chungjungsoo.gptmobile.data.openrouter.account.usd
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppShapes
import dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterSettingsViewModel
import dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterWorkspaceState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterSettingsScreen(
    viewModel: OpenRouterSettingsViewModel,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    onManageProfiles: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val key by viewModel.draftKey.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.clearAccountReadSession() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.clearAccountReadSession()
        }
    }
    var discard by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    val hasDraft = state.dirty || key.isNotBlank() || state.endpointDraft != state.connection?.apiUrl.orEmpty()
    val leave = { if (hasDraft) discard = true else onNavigationClick() }
    BackHandler(enabled = hasDraft) { discard = true }
    val setupScroll = rememberLazyListState()
    val routingScroll = rememberLazyListState()
    val accountScroll = rememberLazyListState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(state.connection?.name ?: "OpenRouter") }, navigationIcon = {
                IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.orw_back)) }
            })
        },
        bottomBar = {
            if (state.dirty) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = viewModel::discard, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.orw_discard)) }
                    Button(onClick = viewModel::apply, enabled = !state.saving && state.nameDraft.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.orw_apply)) }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OpenRouterTabs(state.tab, state.unlocked, viewModel::selectTab)
            if (!state.unlocked) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Lock, null)
                    Text(stringResource(R.string.orw_locked), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.loading || state.validating || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                state = when (state.tab) {
                    OpenRouterTab.SETUP -> setupScroll
                    OpenRouterTab.ROUTING -> routingScroll
                    OpenRouterTab.ACCOUNT -> accountScroll
                },
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.saveError) item { Text(stringResource(R.string.orw_save_error), color = MaterialTheme.colorScheme.error) }
                if (state.saved) item { Text(stringResource(R.string.orw_saved)) }
                when (state.tab) {
                    OpenRouterTab.SETUP -> item { OpenRouterSetup(state, key, viewModel, { disconnect = true }, onManageProfiles, onOpenProfile) }
                    OpenRouterTab.ROUTING -> if (state.unlocked) item { OpenRouterRouting(state, viewModel, onOpenProfile) }
                    OpenRouterTab.ACCOUNT -> if (state.unlocked) item { OpenRouterAccount(state, viewModel) }
                }
            }
        }
    }
    if (discard) {
        AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.orw_unsaved)) }, text = { Text(stringResource(R.string.orw_unsaved_detail)) }, confirmButton = {
            TextButton(onClick = {
                viewModel.discard()
                onNavigationClick()
            }) { Text(stringResource(R.string.orw_discard)) }
        }, dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.orw_keep_editing)) } })
    }
    if (disconnect) {
        AlertDialog(onDismissRequest = { disconnect = false }, title = { Text(stringResource(R.string.orw_disconnect)) }, text = { Text(stringResource(R.string.orw_disconnect_detail)) }, confirmButton = {
            TextButton(onClick = {
                disconnect = false
                viewModel.disconnect()
            }) { Text(stringResource(R.string.orw_remove_key)) }
        }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.orw_cancel)) } })
    }
}

/** Locked colors are opaque neutral tokens, with exactly one 0.50 alpha layer and no click action. */
@Composable
internal fun OpenRouterTabs(tab: OpenRouterTab, unlocked: Boolean, select: (OpenRouterTab) -> Unit) {
    val labels = listOf(stringResource(R.string.orw_setup), stringResource(R.string.orw_routing), stringResource(R.string.orw_account))
    val icons = listOf(Icons.Rounded.Key, Icons.Rounded.Route, Icons.Rounded.Wallet)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OpenRouterTab.entries.forEachIndexed { index, item ->
            val enabled = item == OpenRouterTab.SETUP || unlocked
            val isSelected = item == tab
            val interaction = if (enabled) {
                Modifier.selectable(selected = isSelected, role = Role.Tab, onClick = { select(item) })
            } else {
                Modifier.semantics {
                    disabled()
                    role = Role.Tab
                    selected = false
                }
            }
            Column(
                Modifier.weight(1f).alpha(if (enabled) 1f else 0.50f)
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, AppShapes.panel)
                    .then(interaction).heightIn(min = 64.dp).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val color = if (enabled && isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1f)
                Icon(icons[index], null, tint = color)
                Text(labels[index], style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
    }
}

@Composable
private fun OpenRouterSetup(state: OpenRouterWorkspaceState, key: String, vm: OpenRouterSettingsViewModel, disconnect: () -> Unit, manage: () -> Unit, profile: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var reveal by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    if (switchTo != null) {
        AlertDialog(onDismissRequest = { switchTo = null }, title = { Text(stringResource(R.string.orw_switch_discard)) }, confirmButton = {
            TextButton(onClick = {
                switchTo?.let(vm::selectConnection)
                switchTo = null
            }) { Text(stringResource(R.string.orw_discard)) }
        }, dismissButton = { TextButton(onClick = { switchTo = null }) { Text(stringResource(R.string.orw_keep_editing)) } })
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHero("OPENROUTER", if (state.unlocked) stringResource(R.string.orw_connected) else stringResource(R.string.orw_connect), stringResource(R.string.orw_hero_detail))
        if (state.connections.size > 1 || state.connection == null) {
            SettingsPanel(stringResource(R.string.orw_choose_connection)) {
                state.connections.forEach { connection -> OutlinedButton(onClick = { if (state.dirty || key.isNotBlank() || state.endpointDraft != state.connection?.apiUrl.orEmpty()) switchTo = connection.uid else vm.selectConnection(connection.uid) }, modifier = Modifier.fillMaxWidth()) { Text(connection.name) } }
                if (state.connection == null) {
                    Button(onClick = vm::createConnection, enabled = !state.loading) {
                        Icon(Icons.Rounded.Add, null)
                        Text(stringResource(R.string.orw_new_connection))
                    }
                }
            }
        }
        if (state.connection == null) return@Column
        if (state.legacyDefaults) {
            SettingsPanel(stringResource(R.string.orw_legacy_defaults)) {
                Text(stringResource(R.string.orw_legacy_help))
                TextButton(onClick = vm::inheritLegacyDefaults) { Text(stringResource(R.string.orw_inherit_defaults)) }
            }
        }
        SettingsPanel(stringResource(R.string.orw_credentials)) {
            OutlinedTextField(state.nameDraft, vm::editName, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.orw_name)) }, singleLine = true)
            if (key.isNotBlank() && state.unlocked) Text(stringResource(R.string.orw_replacement), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                key, vm::editKey, Modifier.fillMaxWidth(), label = { Text(if (state.keyCount > 0) stringResource(R.string.orw_replacement_key) else stringResource(R.string.orw_api_key)) },
                enabled = !state.loading,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { vm.validate() }),
                trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.orw_reveal)) } }
            )
            TextButton(onClick = { clipboard.getText()?.text?.let(vm::editKey) }) {
                Icon(Icons.Rounded.ContentPaste, null)
                Text(stringResource(R.string.orw_paste))
            }
            if (state.keyCount > 1) Text(stringResource(R.string.orw_multiple_keys), style = MaterialTheme.typography.bodySmall)
            ExpandSection(stringResource(R.string.orw_endpoint)) {
                Text(stringResource(R.string.orw_region_help))
                OpenRouterIdentity.endpoints.forEachIndexed { index, endpoint ->
                    FilterChip(selected = state.endpointDraft.trimEnd('/') == endpoint, onClick = { vm.editEndpoint(endpoint) }, label = { Text(listOf(stringResource(R.string.orw_global), stringResource(R.string.orw_europe), stringResource(R.string.orw_us))[index]) })
                }
                Text(state.endpointDraft, style = MaterialTheme.typography.bodySmall)
                if (state.endpointDraft.trimEnd('/') !in OpenRouterIdentity.endpoints) Text(stringResource(R.string.orw_gateway_help))
            }
            Button(onClick = { vm.validate() }, enabled = !state.validating && !state.loading && (key.isNotBlank() || state.keyCount > 0), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.CheckCircle, null)
                Text(if (key.isNotBlank() && state.keyCount > 0) stringResource(R.string.orw_validate_replacement) else stringResource(R.string.orw_validate))
            }
            if (state.validating) TextButton(onClick = vm::cancelValidation) { Text(stringResource(R.string.orw_cancel)) }
            state.failure?.let { Text(accountFailure(it), color = MaterialTheme.colorScheme.error) }
            state.snapshot?.let { Text(stringResource(R.string.orw_verified) + ": " + time(it.fetchedAt), style = MaterialTheme.typography.bodySmall) }
            if (state.keyCount > 0) TextButton(onClick = disconnect) { Text(stringResource(R.string.orw_remove_key)) }
            ExternalAction(stringResource(R.string.orw_get_key), "https://openrouter.ai/settings/keys")
            ExternalAction(stringResource(R.string.orw_help), "https://openrouter.ai/docs/quickstart")
            ExpandSection(stringResource(R.string.orw_diagnostics)) {
                Text(vm.safeDiagnostics(), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.safeDiagnostics())) }) { Text(stringResource(R.string.orw_copy_diagnostics)) }
            }
        }
        SettingsPanel(stringResource(R.string.orw_profiles)) {
            Text(stringResource(R.string.orw_profile_help))
            state.profiles.forEach { item ->
                OutlinedButton(onClick = { profile(item.uid) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.name)
                        Text(item.model, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = manage) { Text(stringResource(R.string.orw_manage_profiles)) }
        }
    }
}

@Composable
private fun OpenRouterRouting(state: OpenRouterWorkspaceState, vm: OpenRouterSettingsViewModel, openProfile: (String) -> Unit) {
    val settings = state.draft
    val policy = settings.routing ?: OpenRouterProviderRouting()
    fun change(value: OpenRouterProviderRouting) = vm.updateDraft { it.copy(routing = value) }
    var selectedUid by remember(state.connection?.uid) { mutableStateOf<String?>(null) }
    val selectedProfile = state.profiles.find { it.uid == selectedUid }
    val effective = remember(policy, selectedProfile) { runCatching { OpenRouterPolicy.resolve(policy, OpenRouterPolicy.decodeProfile(selectedProfile?.openRouterRouting).second) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHero(stringResource(R.string.orw_routing), stringResource(R.string.orw_route_title), stringResource(R.string.orw_route_help))
        SettingsPanel(stringResource(R.string.orw_effective_route)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedUid == null, onClick = { selectedUid = null }, label = { Text(stringResource(R.string.orw_provider_defaults)) })
                state.profiles.forEach { item -> FilterChip(selected = item.uid == selectedUid, onClick = { selectedUid = item.uid }, label = { Text(item.name) }) }
            }
            Text(selectedProfile?.model ?: stringResource(R.string.orw_provider_defaults), style = MaterialTheme.typography.titleMedium)
            if (effective.isFailure) {
                Text(stringResource(R.string.orw_policy_conflict), color = MaterialTheme.colorScheme.error)
            } else {
                val resolved = effective.getOrThrow()
                Text(stringResource(R.string.orw_strategy) + ": " + (resolved.sort ?: stringResource(R.string.orw_balanced)))
                if (resolved.only != null) Text(stringResource(R.string.orw_allowed) + ": " + resolved.only.joinToString())
                if (resolved.zdr == true) Text(stringResource(R.string.orw_zdr))
                if (resolved.dataCollection == "deny") Text(stringResource(R.string.orw_data_collection))
            }
            selectedProfile?.let { profile ->
                val model = state.catalog.find { it.id == profile.model }
                Text(stringResource(R.string.orw_catalog_price), style = MaterialTheme.typography.labelLarge)
                val green = accessiblePriceColor(MaterialTheme.colorScheme.surfaceContainerLow)
                Text(stringResource(R.string.orw_input) + ": " + usd(perMillion(model?.pricing?.prompt)) + " / 1M", color = green)
                Text(stringResource(R.string.orw_output) + ": " + usd(perMillion(model?.pricing?.completion)) + " / 1M", color = green)
                model?.supportedParameters?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                if (model == null && state.catalogAt != null) Text(stringResource(R.string.orw_not_eligible))
                TextButton(onClick = { vm.compareProviders(profile.model) }, enabled = !state.endpointLoading) { Text(stringResource(R.string.orw_compare)) }
                ExternalAction(stringResource(R.string.orw_model_details), profile.model.split('/').fold(android.net.Uri.Builder().scheme("https").authority("openrouter.ai")) { uri, segment -> uri.appendPath(segment) }.build().toString())
                TextButton(onClick = { openProfile(profile.uid) }) { Text(stringResource(R.string.orw_profile_extras)) }
            }
            if (state.catalogLoading || state.endpointLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.catalogAt?.let { Text((if (state.catalogError) stringResource(R.string.orw_stale) else stringResource(R.string.orw_updated)) + " · " + time(it), style = MaterialTheme.typography.bodySmall) }
            if (state.catalogError) Text(stringResource(R.string.orw_catalog_error))
            TextButton(onClick = vm::refreshCatalog, enabled = !state.catalogLoading) {
                Icon(Icons.Rounded.Refresh, null)
                Text(stringResource(R.string.orw_refresh_prices))
            }
            val endpoints = ((state.endpoints?.get("data") as? JsonObject)?.get("endpoints") as? JsonArray).orEmpty()
            endpoints.take(30).forEach { value ->
                (value as? JsonObject)?.let { endpoint ->
                    HorizontalDivider()
                    Text(endpoint.text("provider_name") ?: endpoint.text("name").orEmpty())
                    val price = endpoint["pricing"] as? JsonObject
                    Text(stringResource(R.string.orw_input) + " " + usd(perMillion(price?.text("prompt"))) + " · " + stringResource(R.string.orw_output) + " " + usd(perMillion(price?.text("completion"))) + " / 1M")
                }
            }
        }
        SettingsPanel(stringResource(R.string.orw_strategy)) {
            Text(stringResource(R.string.orw_preset_help))
            listOf(null to stringResource(R.string.orw_balanced), "price" to stringResource(R.string.orw_lowest), "latency" to stringResource(R.string.orw_fast), "throughput" to stringResource(R.string.orw_throughput)).forEach { (value, label) ->
                FilterChip(selected = policy.sort == value, onClick = { change(policy.copy(sort = value)) }, label = { Text(label) })
            }
            TextButton(onClick = vm::loadProviders, enabled = !state.providersLoading) { Text(stringResource(R.string.orw_provider_directory)) }
            if (state.providersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.providers.isNotEmpty()) {
                ProviderChooser(stringResource(R.string.orw_preferred), policy.order, state.providers, true) { change(policy.copy(order = it)) }
                ProviderChooser(stringResource(R.string.orw_allowed), policy.only, state.providers, false) { change(policy.copy(only = it)) }
                ProviderChooser(stringResource(R.string.orw_excluded), policy.ignore, state.providers, false) { change(policy.copy(ignore = it)) }
            }
            ProviderList(stringResource(R.string.orw_preferred), policy.order) { change(policy.copy(order = it)) }
            ProviderList(stringResource(R.string.orw_allowed), policy.only) { change(policy.copy(only = it)) }
            ProviderList(stringResource(R.string.orw_excluded), policy.ignore) { change(policy.copy(ignore = it)) }
            Text(stringResource(R.string.orw_slugs), style = MaterialTheme.typography.bodySmall)
            Toggle(stringResource(R.string.orw_fallbacks), stringResource(R.string.orw_fallback_help), policy.allowFallbacks != false) { change(policy.copy(allowFallbacks = it)) }
            Toggle(stringResource(R.string.orw_parameters), stringResource(R.string.orw_parameters_help), policy.requireParameters == true) { change(policy.copy(requireParameters = it)) }
        }
        SettingsPanel(stringResource(R.string.orw_price_privacy)) {
            DecimalField(stringResource(R.string.orw_input_cap), policy.maxPrice?.prompt) { change(policy.copy(maxPrice = (policy.maxPrice ?: OpenRouterMaxPrice()).copy(prompt = it))) }
            DecimalField(stringResource(R.string.orw_output_cap), policy.maxPrice?.completion) { change(policy.copy(maxPrice = (policy.maxPrice ?: OpenRouterMaxPrice()).copy(completion = it))) }
            Text(stringResource(R.string.orw_ceiling_help), style = MaterialTheme.typography.bodySmall)
            Toggle(stringResource(R.string.orw_data_collection), stringResource(R.string.orw_privacy_help), policy.dataCollection == "deny") { change(policy.copy(dataCollection = if (it) "deny" else null)) }
            Toggle(stringResource(R.string.orw_zdr), stringResource(R.string.orw_zdr_help), policy.zdr == true) { change(policy.copy(zdr = if (it) true else null)) }
            Toggle(stringResource(R.string.orw_no_storage), stringResource(R.string.orw_no_storage_help), settings.noRemoteResponseStorage) { value -> vm.updateDraft { it.copy(noRemoteResponseStorage = value, responseCachingEnabled = if (value) false else it.responseCachingEnabled) } }
        }
        SettingsPanel(stringResource(R.string.orw_advanced)) {
            ExpandSection(stringResource(R.string.orw_performance)) {
                DecimalField(stringResource(R.string.orw_min_throughput), policy.preferredMinThroughput as? JsonPrimitive) { change(policy.copy(preferredMinThroughput = it)) }
                DecimalField(stringResource(R.string.orw_max_latency), policy.preferredMaxLatency as? JsonPrimitive) { change(policy.copy(preferredMaxLatency = it)) }
                Text(stringResource(R.string.orw_performance_help))
                ProviderList(stringResource(R.string.orw_quantizations), policy.quantizations) { change(policy.copy(quantizations = it)) }
                DecimalField(stringResource(R.string.orw_image_cap), policy.maxPrice?.image) { change(policy.copy(maxPrice = (policy.maxPrice ?: OpenRouterMaxPrice()).copy(image = it))) }
                DecimalField(stringResource(R.string.orw_request_cap), policy.maxPrice?.request) { change(policy.copy(maxPrice = (policy.maxPrice ?: OpenRouterMaxPrice()).copy(request = it))) }
                Toggle(stringResource(R.string.orw_distillable), stringResource(R.string.orw_distillable_help), policy.enforceDistillableText == true) { change(policy.copy(enforceDistillableText = if (it) true else null)) }
            }
            ExpandSection(stringResource(R.string.orw_caches)) {
                Toggle(stringResource(R.string.orw_sticky), stringResource(R.string.orw_sticky_help), settings.stickySessionRoutingEnabled) { value -> vm.updateDraft { it.copy(stickySessionRoutingEnabled = value) } }
                Toggle(stringResource(R.string.orw_response_cache), stringResource(R.string.orw_cache_help), settings.responseCachingEnabled, !settings.noRemoteResponseStorage) { value -> vm.updateDraft { it.copy(responseCachingEnabled = value) } }
                NumberStepper(stringResource(R.string.orw_remote_ttl), settings.responseCacheTtlSeconds, 1..86400) { value -> vm.updateDraft { it.copy(responseCacheTtlSeconds = value) } }
                var requestedFresh by remember { mutableStateOf(false) }
                TextButton(onClick = {
                    vm.freshNextRequest()
                    requestedFresh = true
                }) { Text(stringResource(R.string.orw_fresh_next)) }
                if (requestedFresh) Text(stringResource(R.string.orw_fresh_queued))
            }
            ExpandSection(stringResource(R.string.orw_batch)) {
                androidx.compose.runtime.LaunchedEffect(state.connection?.uid) { vm.refreshQueueCache() }
                Text(stringResource(R.string.orw_batch_retention))
                Toggle(stringResource(R.string.orw_batch_enable), stringResource(R.string.orw_batch_help), settings.batchingEnabled) { value -> vm.updateDraft { it.copy(batchingEnabled = value) } }
                NumberStepper(stringResource(R.string.orw_batch_size), settings.batchSize, 1..100) { value -> vm.updateDraft { it.copy(batchSize = value) } }
                NumberStepper(stringResource(R.string.orw_flush), settings.flushTimeoutMs.toInt(), 1000..30000) { value -> vm.updateDraft { it.copy(flushTimeoutMs = value.toLong()) } }
                NumberStepper(stringResource(R.string.orw_retries), settings.maxRetries, 0..10) { value -> vm.updateDraft { it.copy(maxRetries = value) } }
                Toggle(stringResource(R.string.orw_local_cache), stringResource(R.string.orw_local_cache_help), settings.cacheEnabled) { value -> vm.updateDraft { it.copy(cacheEnabled = value) } }
                NumberStepper(stringResource(R.string.orw_local_ttl), settings.cacheTtlSeconds, 0..86400) { value -> vm.updateDraft { it.copy(cacheTtlSeconds = value) } }
                Text(stringResource(R.string.orw_cache_entries) + ": " + (state.queueCacheCount?.toString() ?: "—"))
                TextButton(onClick = { vm.refreshQueueCache(true) }, enabled = (state.queueCacheCount ?: 0) > 0) { Text(stringResource(R.string.orw_clear_local_cache)) }
            }
            OpenRouterBatchWorkspace(state, vm)
            Toggle(stringResource(R.string.orw_attribution), stringResource(R.string.orw_attribution_help), settings.attributionEnabled) { value -> vm.updateDraft { it.copy(attributionEnabled = value) } }
        }
    }
}

@Composable
private fun OpenRouterAccount(state: OpenRouterWorkspaceState, vm: OpenRouterSettingsViewModel) {
    val data = state.snapshot?.data ?: return
    fun amount(value: String?) = if (state.hideAmounts) "••••" else usd(value)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsHero(stringResource(R.string.orw_account), stringResource(R.string.orw_key_scope), stringResource(R.string.orw_amount_help))
        SettingsPanel(stringResource(R.string.orw_key_identity)) {
            data.text("label")?.let { ValueRow(stringResource(R.string.orw_key_label), "••••" + if (it.length > 8) it.takeLast(4) else "") }
            Text(stringResource(R.string.orw_inference_role), style = MaterialTheme.typography.bodySmall)
            data.text("is_free_tier")?.let { ValueRow(stringResource(R.string.orw_free_tier), it) }
            ExpandSection(stringResource(R.string.orw_account_context)) {
                data.text("organization_id")?.let { ValueRow(stringResource(R.string.orw_organization_id), it) }
                data.text("workspace_id")?.let { ValueRow(stringResource(R.string.orw_workspace_id), it) }
                (data["allowed_data_regions"] as? JsonArray)?.let { ValueRow(stringResource(R.string.orw_regions), it.mapNotNull { region -> (region as? JsonPrimitive)?.content }.joinToString()) }
            }
        }
        SettingsPanel(stringResource(R.string.orw_allowance)) {
            Text(amount(data.money("limit_remaining")), style = MaterialTheme.typography.headlineMedium)
            ValueRow(stringResource(R.string.orw_key_limit), if (data.money("limit") == null) stringResource(R.string.orw_no_cap) else amount(data.money("limit")))
            Text(stringResource(R.string.orw_no_unlimited), style = MaterialTheme.typography.bodySmall)
            data.text("limit_reset")?.let { ValueRow(stringResource(R.string.orw_reset), it) }
            data.text("expires_at")?.let { ValueRow(stringResource(R.string.orw_expiry), it) }
            data.text("include_byok_in_limit")?.let { ValueRow(stringResource(R.string.orw_byok_limit), it) }
            if (state.snapshot.exhausted) Text(stringResource(R.string.orw_exhausted), color = MaterialTheme.colorScheme.error)
            Text((if (state.stale) stringResource(R.string.orw_stale) else stringResource(R.string.orw_updated)) + " · " + time(state.snapshot.fetchedAt), style = MaterialTheme.typography.bodySmall)
            state.failure?.let { Text(accountFailure(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { vm.validate(false) }, enabled = !state.validating) {
                Icon(Icons.Rounded.Refresh, null)
                Text(stringResource(R.string.orw_refresh_account))
            }
            TextButton(onClick = vm::hideAmounts) { Text(if (state.hideAmounts) stringResource(R.string.orw_show_amounts) else stringResource(R.string.orw_hide_amounts)) }
        }
        (data["effective_budget"] as? JsonObject)?.let { budget ->
            SettingsPanel(stringResource(R.string.orw_effective_budget)) {
                ValueRow(stringResource(R.string.orw_scope), budget.text("scope") ?: "—")
                ValueRow(stringResource(R.string.orw_remaining), amount(budget.money("remaining_usd")))
                ValueRow(stringResource(R.string.orw_spent), amount(budget.money("spend_usd")))
                ValueRow(stringResource(R.string.orw_limit), amount(budget.money("limit_usd")))
                budget.text("resets_at")?.let { ValueRow(stringResource(R.string.orw_resets_at), it) }
                Text(stringResource(R.string.orw_budget_help), style = MaterialTheme.typography.bodySmall)
                ExpandSection(stringResource(R.string.orw_budget_details)) {
                    (data["budgets"] as? JsonArray).orEmpty().forEach {
                        (it as? JsonObject)?.let { row ->
                            ValueRow(row.text("scope") ?: "—", amount(row.money("remaining_usd")))
                        }
                    }
                }
            }
        }
        SettingsPanel(stringResource(R.string.orw_usage)) {
            listOf("usage_daily" to stringResource(R.string.orw_daily), "usage_weekly" to stringResource(R.string.orw_weekly), "usage_monthly" to stringResource(R.string.orw_monthly), "usage" to stringResource(R.string.orw_lifetime)).forEach { (field, label) ->
                data.money(field)?.let { ValueRow(label, amount(it)) }
            }
            Text(stringResource(R.string.orw_period_help), style = MaterialTheme.typography.bodySmall)
            ExpandSection(stringResource(R.string.orw_byok)) {
                listOf("byok_usage_daily" to stringResource(R.string.orw_daily), "byok_usage_weekly" to stringResource(R.string.orw_weekly), "byok_usage_monthly" to stringResource(R.string.orw_monthly), "byok_usage" to stringResource(R.string.orw_lifetime)).forEach { (field, label) -> data.money(field)?.let { ValueRow(label, amount(it)) } }
            }
        }
        (data["free_model_daily_requests"] as? JsonObject)?.let { quota ->
            SettingsPanel(stringResource(R.string.orw_free_requests)) {
                quota.text("used")?.let { ValueRow(stringResource(R.string.orw_used), it) }
                quota.text("remaining")?.let { ValueRow(stringResource(R.string.orw_remaining), it) }
                quota.text("limit")?.let { ValueRow(stringResource(R.string.orw_limit), it) }
            }
        }
        SettingsPanel(stringResource(R.string.orw_device_activity)) {
            Text(stringResource(R.string.orw_device_activity_help))
            val records = state.receipts
            ValueRow(stringResource(R.string.orw_requests), records.size.toString())
            ValueRow(stringResource(R.string.orw_failures), records.count { it.status in setOf("failed", "cancelled") }.toString())
            val confirmed = records.mapNotNull { it.costUsd?.toBigDecimalOrNull() }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
            ValueRow(stringResource(R.string.orw_recorded_cost), amount(confirmed.toPlainString()))
            ValueRow(stringResource(R.string.orw_pending_cost), records.count { it.costUsd == null }.toString())
            TextButton(onClick = vm::reconcileReceipts, enabled = !state.reconciling) { Text(stringResource(R.string.orw_reconcile)) }
            if (state.reconciling) LinearProgressIndicator(Modifier.fillMaxWidth())
            ExpandSection(stringResource(R.string.orw_recent_receipts)) {
                records.take(30).forEach { receipt ->
                    Text(receipt.resolvedModel ?: receipt.context.model, style = MaterialTheme.typography.titleSmall)
                    Text(receipt.context.role + " · " + receipt.status + " · " + time(receipt.createdAt), style = MaterialTheme.typography.bodySmall)
                    Text(if (receipt.costUsd == null) stringResource(R.string.orw_pending_cost) else amount(receipt.costUsd))
                    Text("${receipt.inputTokens?.toString() ?: "—"} / ${receipt.outputTokens?.toString() ?: "—"} · ${receipt.durationMs} ms", style = MaterialTheme.typography.bodySmall)
                    receipt.provider?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    receipt.cacheStatus?.let { Text("Cache · $it", style = MaterialTheme.typography.bodySmall) }
                    receipt.cachedTokens?.let { ValueRow(stringResource(R.string.orw_cached_tokens), it.toString()) }
                    HorizontalDivider()
                }
            }
        }
        OpenRouterAccountReadAccess(state, vm)
        SettingsPanel(stringResource(R.string.orw_wallet)) {
            Text(stringResource(R.string.orw_wallet_help))
            ExternalAction(stringResource(R.string.orw_open_wallet), "https://openrouter.ai/settings/credits")
            ExternalAction(stringResource(R.string.orw_open_usage), "https://openrouter.ai/activity")
            ExternalAction(stringResource(R.string.orw_manage_keys), "https://openrouter.ai/settings/keys")
            ExternalAction(stringResource(R.string.orw_manage_byok), "https://openrouter.ai/settings/integrations")
            ExternalAction(stringResource(R.string.orw_privacy), "https://openrouter.ai/settings/privacy")
        }
    }
}

@Composable
private fun ExpandSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (expanded) Icons.Rounded.Remove else Icons.Rounded.Add, null)
        Text(title)
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Toggle(title: String, help: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.weight(1f))
        SettingsHelpIcon(help, title = title)
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun ProviderList(label: String, values: List<String>?, change: (List<String>?) -> Unit) {
    var text by remember(values) { mutableStateOf(values.orEmpty().joinToString(", ")) }
    OutlinedTextField(text, { value ->
        text = value
        change(value.split(',').map(String::trim).filter(String::isNotBlank).distinct().takeIf { it.isNotEmpty() })
    }, Modifier.fillMaxWidth(), label = { Text(label) })
}

@Composable
private fun DecimalField(label: String, value: JsonPrimitive?, change: (JsonPrimitive?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.content.orEmpty()) }
    OutlinedTextField(
        text,
        { raw ->
            text = raw
            change(if (raw.isBlank()) null else runCatching { Json.parseToJsonElement(raw) as? JsonPrimitive }.getOrNull() ?: JsonPrimitive(raw))
        },
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        isError = text.isNotBlank() && (text.toBigDecimalOrNull()?.signum()?.let { it < 0 } ?: true)
    )
}

@Composable
private fun NumberStepper(label: String, value: Int, range: IntRange, change: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { change((value - 1).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, stringResource(R.string.orw_decrease)) }
        OutlinedTextField(value.toString(), { it.toIntOrNull()?.let { number -> change(number.coerceIn(range)) } }, Modifier.weight(1f), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        IconButton(onClick = { change((value + 1).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, stringResource(R.string.orw_increase)) }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ExternalAction(label: String, url: String) {
    val handler = LocalUriHandler.current
    TextButton(onClick = { handler.openUri(url) }) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
        Text(label)
    }
}

private fun time(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable
private fun accountFailure(value: AccountFailure): String = when (value) {
    AccountFailure.INVALID -> stringResource(R.string.orw_invalid_key)
    AccountFailure.EXPIRED -> stringResource(R.string.orw_expired_key)
    AccountFailure.ROLE -> stringResource(R.string.orw_wrong_role)
    AccountFailure.FORBIDDEN -> stringResource(R.string.orw_forbidden)
    AccountFailure.BILLING -> stringResource(R.string.orw_billing)
    AccountFailure.RATE_LIMITED -> stringResource(R.string.orw_rate_limited)
    AccountFailure.MALFORMED -> stringResource(R.string.orw_malformed)
    AccountFailure.ENDPOINT -> stringResource(R.string.orw_endpoint_error)
    AccountFailure.NETWORK -> stringResource(R.string.orw_network_error)
}

@Composable
private fun ProviderChooser(title: String, selected: List<String>?, providers: List<JsonObject>, ordered: Boolean, apply: (List<String>?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var selection by remember(selected) { mutableStateOf(selected.orEmpty()) }
    var query by remember { mutableStateOf("") }
    OutlinedButton(onClick = {
        selection = selected.orEmpty()
        open = true
    }, modifier = Modifier.fillMaxWidth()) { Text(title) }
    if (open) {
        AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.orw_search_providers)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (ordered && selection.isNotEmpty()) {
                        item {
                            selection.forEachIndexed { index, slug ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(slug, Modifier.weight(1f))
                                    TextButton(onClick = { selection = selection.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Text(stringResource(R.string.orw_move_up)) }
                                    TextButton(onClick = { selection = selection.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < selection.lastIndex) { Text(stringResource(R.string.orw_move_down)) }
                                }
                            }
                        }
                    }
                    providers.filter { it.text("name").orEmpty().contains(query, true) || it.text("slug").orEmpty().contains(query, true) }.forEach { provider ->
                        item {
                            val slug = provider.text("slug").orEmpty()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = slug in selection, onCheckedChange = { checked -> selection = if (checked) (selection + slug).distinct() else selection - slug })
                                Column(Modifier.weight(1f)) {
                                    Text(provider.text("name") ?: slug)
                                    Text(slug, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            listOf("privacy_policy_url" to stringResource(R.string.orw_privacy_link), "terms_of_service_url" to stringResource(R.string.orw_terms), "status_page_url" to stringResource(R.string.orw_status_link)).forEach { (field, label) ->
                                provider.text(field)?.takeIf { url -> runCatching { java.net.URI(url).let { it.scheme == "https" && it.host != null && it.userInfo == null } }.getOrDefault(false) }?.let { ExternalAction(label, it) }
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                apply(selection.takeIf { it.isNotEmpty() })
                open = false
            }) { Text(stringResource(R.string.orw_apply)) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.orw_cancel)) } })
    }
}

@Composable
private fun OpenRouterAccountReadAccess(state: OpenRouterWorkspaceState, vm: OpenRouterSettingsViewModel) {
    var credential by remember { mutableStateOf("") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) credential = "" }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            credential = ""
            vm.clearAccountReadSession()
        }
    }
    SettingsPanel(stringResource(R.string.orw_separate_access)) {
        ExpandSection(stringResource(R.string.orw_read_access)) {
            Text(stringResource(R.string.orw_management_warning))
            Text(stringResource(R.string.orw_separate_scope))
            OutlinedTextField(credential, { credential = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.orw_management_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = {
                vm.connectAccountReadAccess(credential)
                credential = ""
            }, enabled = credential.isNotBlank() && !state.accountReadLoading) { Text(stringResource(R.string.orw_connect_read)) }
            if (state.accountReadLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.accountReadError) Text(stringResource(R.string.orw_read_restricted))
            state.wallet?.let { wallet ->
                ValueRow(stringResource(R.string.orw_credits_total), if (state.hideAmounts) "••••" else usd(wallet.money("total_credits")))
                ValueRow(stringResource(R.string.orw_credits_used), if (state.hideAmounts) "••••" else usd(wallet.money("total_usage")))
                val remaining = wallet.money("total_credits")?.toBigDecimalOrNull()?.let { total -> wallet.money("total_usage")?.toBigDecimalOrNull()?.let { total - it } }
                ValueRow(stringResource(R.string.orw_wallet_remaining), if (state.hideAmounts) "••••" else usd(remaining?.toPlainString()))
            }
            state.accountReadAt?.let { Text(stringResource(R.string.orw_updated) + " · " + time(it)) }
            if (state.accountActivity != null) {
                Text(stringResource(R.string.orw_analytics_period), style = MaterialTheme.typography.bodySmall)
                state.accountActivity.take(90).forEach {
                    (it as? JsonObject)?.let { row ->
                        Text(row.text("date").orEmpty() + " · " + row.text("model").orEmpty(), style = MaterialTheme.typography.bodySmall)
                        ValueRow(row.text("provider_name").orEmpty(), if (state.hideAmounts) "••••" else usd(row.money("usage")))
                    }
                }
            }
            TextButton(onClick = vm::clearAccountReadSession) { Text(stringResource(R.string.orw_clear_session)) }
        }
    }
}

@Composable
private fun OpenRouterBatchWorkspace(state: OpenRouterWorkspaceState, vm: OpenRouterSettingsViewModel) {
    var cancel by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var exportState by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exportFailure = stringResource(R.string.orw_export_failure)
    val exportSuccess = stringResource(R.string.orw_export_success)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val result = exportId?.let { vm.exportBatch(it) }
                try {
                    if (result == null) {
                        exportState = exportFailure
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(result) } }
                        exportState = exportSuccess
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    exportState = exportFailure
                }
            }
        }
    }
    ExpandSection(stringResource(R.string.orw_jobs)) {
        Text(stringResource(R.string.orw_jobs_scope))
        TextButton(onClick = vm::refreshBatches, enabled = !state.batchLoading) {
            Icon(Icons.Rounded.Refresh, null)
            Text(stringResource(R.string.orw_refresh_jobs))
        }
        if (state.batchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.batchError) Text(stringResource(R.string.orw_batch_error))
        state.batchUpdatedAt?.let { Text(stringResource(R.string.orw_updated) + " · " + time(it)) }
        state.batches.forEach { batch ->
            Text(batch.model ?: batch.id, style = MaterialTheme.typography.titleSmall)
            Text(batch.status + " · ${batch.completedRequests}/${batch.totalRequests} · ${batch.failedRequests} " + stringResource(R.string.orw_failures))
            if (batch.totalRequests > 0) LinearProgressIndicator(progress = { ((batch.completedRequests + batch.failedRequests).toFloat() / batch.totalRequests).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (batch.status == "completed") {
                TextButton(onClick = {
                    exportId = batch.id
                    launcher.launch("openrouter-batch-results.json")
                }) { Text(stringResource(R.string.orw_export_results)) }
            }
            if (batch.status in setOf("validating", "in_progress", "finalizing")) {
                TextButton(onClick = {
                    cancel = batch.id
                    delete = false
                }, enabled = !state.batchLoading) { Text(stringResource(R.string.orw_cancel_batch)) }
            }
            if (batch.status in setOf("completed", "failed", "expired", "cancelled")) {
                TextButton(onClick = {
                    cancel = batch.id
                    delete = true
                }, enabled = !state.batchLoading) { Text(stringResource(R.string.orw_delete_batch)) }
            }
            HorizontalDivider()
        }
        exportState?.let { Text(it) }
    }
    if (cancel != null) {
        AlertDialog(onDismissRequest = { cancel = null }, title = { Text(if (delete) stringResource(R.string.orw_delete_batch) else stringResource(R.string.orw_cancel_batch)) }, text = { Text(if (delete) stringResource(R.string.orw_delete_batch_help) else stringResource(R.string.orw_cancel_batch_help)) }, confirmButton = {
            TextButton(onClick = {
                cancel?.let { vm.cancelBatch(it, delete) }
                cancel = null
            }) { Text(stringResource(R.string.orw_confirm)) }
        }, dismissButton = { TextButton(onClick = { cancel = null }) { Text(stringResource(R.string.orw_keep)) } })
    }
}

internal fun accessiblePriceColor(surface: Color): Color {
    val light = surface.luminance()
    val base = if (light < 0.18f) Color(0xFF80DBA4) else Color(0xFF146B37)
    val endpoint = if (light < 0.18f) Color.White else Color.Black
    return (0..10).map { androidx.compose.ui.graphics.lerp(base, endpoint, it / 10f) }.firstOrNull { candidate ->
        val other = candidate.luminance()
        (maxOf(light, other) + 0.05f) / (minOf(light, other) + 0.05f) >= 4.5f
    } ?: endpoint
}
