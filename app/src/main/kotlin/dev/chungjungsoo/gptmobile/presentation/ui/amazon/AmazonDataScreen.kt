package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository
import dev.chungjungsoo.gptmobile.data.amazon.AmazonObservationEntity
import dev.chungjungsoo.gptmobile.data.amazon.AmazonWatchEntity
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AmazonDataScreen(
    onBack: () -> Unit,
    viewModel: AmazonDataViewModel = hiltViewModel(),
    initialOwner: String? = null,
    initialMarket: AmazonFreeMarket = AmazonFreeMarket.CANADA,
    initialAsin: String = ""
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var ownerMenu by remember { mutableStateOf(false) }
    var market by remember(initialMarket) { mutableStateOf(initialMarket) }
    var asin by remember(initialAsin) { mutableStateOf(initialAsin) }
    var createWatch by remember { mutableStateOf(false) }
    var editWatch by remember { mutableStateOf<AmazonWatchEntity?>(null) }
    var deleteWatch by remember { mutableStateOf<AmazonWatchEntity?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    LaunchedEffect(initialOwner, initialMarket, initialAsin) {
        if (initialOwner != null && initialAsin.isNotBlank()) viewModel.openListing(initialOwner, initialMarket, initialAsin)
    }
    if (createWatch || editWatch != null) {
        AmazonWatchDialog(
            ownerName = state.selectedProfile?.name ?: state.owner,
            market = editWatch?.let { AmazonFreeMarket.fromDomain(it.marketplace) } ?: market,
            asin = editWatch?.asin ?: asin,
            watch = editWatch,
            enabled = state.canSave && !state.busy,
            onDismiss = {
                createWatch = false
                editWatch = null
            },
            onSave = { selectedMarket, selectedAsin, target ->
                viewModel.saveWatch(selectedMarket, selectedAsin, target, editWatch) {
                    createWatch = false
                    editWatch = null
                }
            }
        )
    }
    deleteWatch?.let { watch ->
        AlertDialog(
            onDismissRequest = { deleteWatch = null },
            title = { Text("Delete this manual watch?") },
            text = { Text("${watch.asin} · ${watch.marketplace}. Its observed price history will be kept.") },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    viewModel.delete(watch)
                    deleteWatch = null
                }) { Text("Delete watch") }
            },
            dismissButton = { TextButton(onClick = { deleteWatch = null }) { Text("Cancel") } }
        )
    }
    if (clearHistory) {
        AlertDialog(
            onDismissRequest = { clearHistory = false },
            title = { Text("Clear this profile's Amazon history?") },
            text = { Text("Remove saved price observations and check outcomes for ${state.selectedProfile?.name ?: state.owner}. Watches, request usage and cooldowns are preserved.") },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    viewModel.clearHistory()
                    clearHistory = false
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } }
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Amazon history & watches") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local observations · Canada/US preview", style = MaterialTheme.typography.titleMedium)
                    Text("History starts with successful checks on this device. Manual targets do not run in the background or send notifications. Unknown seller, condition, variant and delivery context cannot trigger a price-drop decision.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !state.busy, onClick = { ownerMenu = true }) { Text("Profile: ${state.selectedProfile?.name ?: state.owner.ifBlank { "Choose a profile" }}") }
                    DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                                viewModel.selectOwner(profile.uid)
                                ownerMenu = false
                            })
                        }
                        // Deleted owners remain manageable locally without transferring ownership to a new profile.
                        val orphanOwners = viewModelOrphanOwners(viewModel)
                        orphanOwners.forEach { owner ->
                            DropdownMenuItem(text = { Text("Deleted profile · ${owner.take(12)}") }, onClick = {
                                viewModel.selectOwner(owner)
                                ownerMenu = false
                            })
                        }
                    }
                    if (!state.canSave || !state.canCheck) Text("Enable Amazon Research Free in Toolkit and this profile's Tools panel to save or check targets. Existing data stays readable here.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AmazonFreeMarket.entries.forEach { option -> FilterChip(selected = market == option, onClick = { market = option }, label = { Text("${option.label} · ${option.currency}") }) }
                    }
                    OutlinedTextField(value = asin, onValueChange = { asin = it.take(10).uppercase(Locale.ROOT) }, label = { Text("ASIN (10 characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.busy && state.owner.isNotBlank() && asin.length == 10, onClick = { viewModel.loadHistory(market, asin) }) { Text("Read local history") }
                        OutlinedButton(enabled = !state.busy && state.canSave, onClick = { createWatch = true }) { Text("Save manual watch") }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.busy) Text("Working…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.history?.let { history ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${state.historyAsin} · ${state.historyMarket.currency}", style = MaterialTheme.typography.titleMedium)
                        Text("${history.retainedCount} retained observations · showing ${history.observations.size}. Retention: 365 days, with latest points preserved for unpaused watches; 50,000-row soft cap.", style = MaterialTheme.typography.bodySmall)
                        if (history.observations.isEmpty()) Text("No history yet. Future successful checks with confirmed currency will build it.")
                        if (history.observations.size == 1) Text("One observation; there is no trend or historical-low evidence.", style = MaterialTheme.typography.bodySmall)
                        history.observations.groupBy { it.seriesKey }.values.forEach { points ->
                            Text(if (points.first().sourceType == "search_page") "Search page · incomplete offer" else "Product page · incomplete offer", style = MaterialTheme.typography.labelLarge)
                            AmazonObservationPlot(points)
                            points.take(10).forEach { point -> Text("${point.currency} ${point.amount} · ${localTime(point.observedAt)}", style = MaterialTheme.typography.bodySmall) }
                            if (points.size > 10) Text("Chart includes ${points.size} observations; latest 10 listed.", style = MaterialTheme.typography.bodySmall)
                        }
                        history.checks.firstOrNull()?.let { Text("Last check: ${localTime(it.attemptedAt)} · ${it.outcome.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.bodySmall) }
                        Text("Dots show individual checks. They do not connect gaps or establish comparable offers. Shipping, tax and coupons are unknown.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Manual watches (${state.watches.size}/20)", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium) }
            if (state.watches.isEmpty()) item { Text("No manual targets saved for this profile.", Modifier.padding(horizontal = 16.dp)) }
            items(state.watches, key = { it.id }) { watch ->
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${watch.asin} · ${watch.marketplace}", style = MaterialTheme.typography.titleSmall)
                        Text("Target ${watch.currency} ${watch.targetAmount} · new condition · exact ASIN")
                        Text(
                            when {
                                state.selectedProfile == null -> "Orphaned · deleted owning profile"
                                watch.state == "PAUSED" -> "Paused"
                                !state.canCheck -> "Awaiting permission"
                                else -> "Awaiting matching price · offer context incomplete"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Last attempt: ${localTime(watch.lastAttemptAt)}\nLast successful lookup: ${localTime(watch.lastSuccessAt)}\nOutcome: ${watch.lastOutcome?.replace('_', ' ')?.lowercase() ?: "Not checked"}", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = state.canCheck && !state.busy && watch.state != "PAUSED", onClick = { viewModel.check(watch) }) { Text("Check now · uses allowance") }
                            TextButton(enabled = !state.busy, onClick = { viewModel.loadHistory(requireNotNull(AmazonFreeMarket.fromDomain(watch.marketplace)), watch.asin) }) { Text("History") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = state.canSave && !state.busy, onClick = { editWatch = watch }) { Text("Edit target") }
                            TextButton(enabled = !state.busy && (watch.state != "PAUSED" || state.canSave), onClick = { viewModel.pause(watch, watch.state != "PAUSED") }) { Text(if (watch.state == "PAUSED") "Resume" else "Pause") }
                            TextButton(enabled = !state.busy, onClick = { deleteWatch = watch }) { Text("Delete") }
                        }
                    }
                }
            }
            item { TextButton(enabled = state.owner.isNotBlank() && !state.busy, modifier = Modifier.padding(16.dp), onClick = { clearHistory = true }) { Text("Clear this profile's history") } }
        }
    }
}

@Composable
private fun viewModelOrphanOwners(viewModel: AmazonDataViewModel): List<String> {
    val orphans by viewModel.orphanOwners.collectAsStateWithLifecycle()
    return orphans
}

@Composable
private fun AmazonObservationPlot(points: List<AmazonObservationEntity>) {
    if (points.size < 2) return
    val prices = points.mapNotNull { it.amount.toDoubleOrNull() }
    if (prices.size != points.size) return
    val first = points.minOf { it.observedAt }
    val last = points.maxOf { it.observedAt }
    val minimum = prices.min()
    val maximum = prices.max()
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "${points.size} separate price observations, from ${localTime(first)} to ${localTime(last)}, ${points.first().currency} $minimum to $maximum. No connected trend line." }) {
        points.forEachIndexed { index, point ->
            val x = if (last == first) 0.5f else ((point.observedAt - first).toDouble() / (last - first)).toFloat()
            val y = if (maximum == minimum) 0.5f else ((prices[index] - minimum) / (maximum - minimum)).toFloat()
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(8.dp.toPx() + x * (size.width - 16.dp.toPx()), 8.dp.toPx() + (1 - y) * (size.height - 16.dp.toPx())))
        }
    }
    Text("${localTime(first)} → ${localTime(last)}\n${points.first().currency} $minimum–$maximum", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AmazonWatchDialog(ownerName: String, market: AmazonFreeMarket, asin: String, watch: AmazonWatchEntity?, enabled: Boolean, onDismiss: () -> Unit, onSave: (AmazonFreeMarket, String, String) -> Unit) {
    var selectedMarket by remember(watch?.id) { mutableStateOf(market) }
    var selectedAsin by remember(watch?.id) { mutableStateOf(asin) }
    var target by remember(watch?.id) { mutableStateOf(watch?.targetAmount.orEmpty()) }
    val valid = Regex("[A-Z0-9]{10}").matches(selectedAsin) && AmazonHistoryRepository.MONEY.matches(target) && target.toBigDecimalOrNull()?.signum() == 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (watch == null) "Save a manual Amazon watch" else "Edit target") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Owner: $ownerName. This explicitly saves a target on this device, including from a temporary chat. It does not grant background checks or notifications.")
                if (watch == null) AmazonFreeMarket.entries.forEach { option -> FilterChip(selected = selectedMarket == option, onClick = { selectedMarket = option }, label = { Text("${option.label} · ${option.currency}") }) }
                OutlinedTextField(selectedAsin, { selectedAsin = it.take(10).uppercase(Locale.ROOT) }, enabled = watch == null, label = { Text("ASIN") }, singleLine = true)
                OutlinedTextField(target, { target = it.take(12) }, label = { Text("Target base price · ${selectedMarket.currency}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Criteria: new condition, exact ASIN, confirmed currency, seller and delivery context required. The current source does not confirm these details; this target remains Awaiting matching price.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = enabled && valid, onClick = { onSave(selectedMarket, selectedAsin, target) }) { Text(if (watch == null) "Save manual watch" else "Save target") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun localTime(time: Long?): String = time?.let { DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "Not yet"
