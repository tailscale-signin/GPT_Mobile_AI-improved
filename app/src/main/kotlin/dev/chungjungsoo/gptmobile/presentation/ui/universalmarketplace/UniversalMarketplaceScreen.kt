package dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceContract
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchAction
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchRequest
import dev.chungjungsoo.gptmobile.data.universalmarketplace.UniversalMarketplaceSettings
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UniversalMarketplaceScreen(onBack: () -> Unit, initialRequest: MarketplaceSearchRequest? = null, viewModel: UniversalMarketplaceViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val installed by viewModel.installation.collectAsStateWithLifecycle()
    val config = installed[MarketplaceContract.INSTALLATION]?.universalMarketplace ?: UniversalMarketplaceSettings()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val saved by viewModel.searches.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Search") }
    var query by rememberSaveable { mutableStateOf(initialRequest?.query.orEmpty()) }
    var country by rememberSaveable(config.country) { mutableStateOf(initialRequest?.country ?: config.country) }
    var city by rememberSaveable(config.city) { mutableStateOf(initialRequest?.city ?: config.city) }
    var currency by rememberSaveable(config.currency) { mutableStateOf(initialRequest?.currency ?: config.currency) }
    var providers by rememberSaveable(config.enabledSources) { mutableStateOf(initialRequest?.providers ?: config.enabledSources) }
    var resultLimit by rememberSaveable(config.maxResults) { mutableStateOf(initialRequest?.maxResults ?: config.maxResults) }
    var price by rememberSaveable { mutableStateOf(initialRequest?.maxPriceMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var radius by rememberSaveable { mutableStateOf(initialRequest?.radiusKm?.toString().orEmpty()) }
    var condition by rememberSaveable { mutableStateOf(initialRequest?.condition ?: "any") }
    var fulfilment by rememberSaveable { mutableStateOf(initialRequest?.fulfilment ?: "any") }
    var sort by rememberSaveable { mutableStateOf(initialRequest?.sort ?: "relevance") }
    var priceBasis by rememberSaveable { mutableStateOf(initialRequest?.priceBasis ?: "item") }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf("all") }
    var detail by remember { mutableStateOf<MarketplaceSearchAction?>(null) }
    var bookmarkProvider by rememberSaveable { mutableStateOf("ebay") }
    var bookmarkUrl by rememberSaveable { mutableStateOf("") }
    var bookmarkNote by rememberSaveable { mutableStateOf("") }
    var deleteSaved by remember { mutableStateOf(false) }
    fun request() = MarketplaceSearchRequest(
        query, providers, country, city, currency,
        radiusKm = radius.takeIf(String::isNotBlank)?.let { requireNotNull(it.toIntOrNull()) { "Enter a whole-number radius." } },
        maxPriceMinor = price.takeIf(String::isNotBlank)?.let { BigDecimal(it).movePointRight(2).longValueExact() },
        priceBasis = priceBasis, fulfilment = fulfilment, condition = condition, sort = sort, maxResults = resultLimit
    ).normalized()
    fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            viewModel.message(failure.message ?: "Check the filters.")
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Marketplace") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Search", "Saved", "Settings").forEach { name -> FilterChip(tab == name, { tab = name }, label = { Text(name) }) }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            when (tab) {
                "Search" -> {
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text("Search marketplaces") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("${city.ifBlank { "Choose city" }} · $country · $currency${price.takeIf(String::isNotBlank)?.let { " · Up to $it" }.orEmpty()}")
                        TextButton(onClick = { filterOpen = !filterOpen }) { Text("Filters") }
                        if (filterOpen) {
                            Text("Requested sources", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MarketplaceContract.providers.forEach { id -> FilterChip(id in providers, { providers = if (id in providers) providers - id else providers + id }, label = { Text(MarketplaceContract.names.getValue(id)) }) }
                            }
                            Choices("Country", MarketplaceContract.countries, country) { country = it }
                            OutlinedTextField(city, { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth())
                            Choices("Currency", MarketplaceContract.currencies, currency) { currency = it }
                            OutlinedTextField(price, { price = it }, label = { Text("Maximum item price ($currency) · optional") }, singleLine = true)
                            Choices("Price basis", listOf("item", "item_plus_shipping"), priceBasis) { priceBasis = it }
                            OutlinedTextField(radius, { radius = it }, label = { Text("Radius km · optional") }, singleLine = true)
                            Choices("Condition", listOf("any", "new", "used", "refurbished"), condition) { condition = it }
                            Choices("Fulfilment", listOf("any", "pickup", "shipping"), fulfilment) { fulfilment = it }
                            Choices("Sort", listOf("relevance", "price_asc", "price_desc", "newest"), sort) { sort = it }
                            Text("Confirm these filters on the destination site. Prices, availability and exact radius are not verified here.")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { attempt { viewModel.search(request()) } }, enabled = !loading && installed[MarketplaceContract.INSTALLATION]?.enabled == true) { Text("Prepare searches") }
                            TextButton(onClick = { attempt { viewModel.saveSearch(request()) } }) { Text("Save search") }
                        }
                        if (installed[MarketplaceContract.INSTALLATION]?.enabled != true) Text("Enable Universal Marketplace in Plugins & Tools to search.")
                        if (loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                        }
                    }
                    result?.let { response ->
                        item {
                            Text("Prepared for: ${response.request.query} · ${response.request.city} · ${response.request.country} · ${response.request.currency}")
                            Text("No listings retrieved. Searches are ready on ${response.searchActions.size} sites.", style = MaterialTheme.typography.titleMedium)
                            Choices("Sources", listOf("all") + response.request.providers, source) { source = it }
                        }
                        items(response.providerStatuses.filter { source == "all" || source == it.provider }, key = { "status:${it.provider}" }) { status ->
                            Text("${MarketplaceContract.names[status.provider]} · ${status.state.replace('_', ' ')}\n${status.message}", style = MaterialTheme.typography.bodySmall)
                        }
                        items(response.searchActions.filter { source == "all" || source == it.provider }, key = { "action:${it.provider}" }) { action -> MarketplaceActionCard(action) { detail = action } }
                    }
                }
                "Saved" -> {
                    item {
                        Text("Saved searches", style = MaterialTheme.typography.titleMedium)
                        if (saved.isEmpty()) Text("No saved searches yet.")
                    }
                    items(saved, key = { "query:${it.id}" }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.title)
                                Row {
                                    TextButton(onClick = {
                                        attempt {
                                            val value = MarketplaceContract.json.decodeFromString(MarketplaceSearchRequest.serializer(), entry.requestJson).normalized()
                                            providers = value.providers
                                            resultLimit = value.maxResults
                                            query = value.query
                                            country = value.country
                                            city = value.city
                                            currency = value.currency
                                            price = value.maxPriceMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
                                            radius = value.radiusKm?.toString().orEmpty()
                                            condition = value.condition
                                            fulfilment = value.fulfilment
                                            sort = value.sort
                                            priceBasis = value.priceBasis
                                            tab = "Search"
                                        }
                                    }) { Text("Use filters") }
                                    TextButton(onClick = { viewModel.deleteSearch(entry.id) }) { Text("Delete") }
                                }
                            }
                        }
                    }
                    item {
                        Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                        Choices("Source", MarketplaceContract.providers, bookmarkProvider) { bookmarkProvider = it }
                        OutlinedTextField(bookmarkUrl, { bookmarkUrl = it }, label = { Text("Marketplace HTTPS URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(bookmarkNote, { bookmarkNote = it }, label = { Text("Your notes") }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { viewModel.bookmark(bookmarkProvider, bookmarkUrl, bookmarkNote) }) { Text("Save bookmark") }
                    }
                    items(bookmarks, key = { "bookmark:${it.id}" }) { entry ->
                        Column {
                            Text(entry.note.ifBlank { MarketplaceContract.names[entry.provider].orEmpty() })
                            SafeMarketplaceLink(entry.provider, entry.url)
                            TextButton(onClick = { viewModel.deleteBookmark(entry.id) }) { Text("Delete") }
                        }
                    }
                }
                else -> item { MarketplaceSettings(config, viewModel::configure, onDelete = { deleteSaved = true }) }
            }
        }
    }
    detail?.let { action -> MarketplaceActionDetails(action, { detail = null }, { viewModel.bookmark(action.provider, action.url, "Search: $query") }) }
    if (deleteSaved) {
        AlertDialog(onDismissRequest = { deleteSaved = false }, title = { Text("Delete saved Marketplace data?") }, text = { Text("Deletes your saved searches, bookmark URLs and notes.") }, confirmButton = {
            TextButton(onClick = {
                viewModel.clearSaved()
                deleteSaved = false
            }) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { deleteSaved = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(title: String, values: List<String>, selected: String, choose: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected == value, { choose(value) }, label = { Text(MarketplaceContract.names[value] ?: value.replace('_', ' ')) }) }
    }
}

@Composable
fun MarketplaceActionCard(action: MarketplaceSearchAction, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(action.label, style = MaterialTheme.typography.titleMedium)
            Text("External search · no retrieved listings", style = MaterialTheme.typography.labelMedium)
            Text("Confirm on site: " + action.pendingFilters.entries.joinToString { "${it.key.replace('_', ' ')}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MarketplaceActionDetails(action: MarketplaceSearchAction, onDismiss: () -> Unit, onSave: (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(MarketplaceContract.names[action.provider].orEmpty()) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(action.notice)
            action.pendingFilters.forEach { (key, value) -> Text("${key.replace('_', ' ')}: $value") }
            SafeMarketplaceLink(action.provider, action.url)
            onSave?.let { TextButton(onClick = it) { Text("Save bookmark") } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun SafeMarketplaceLink(provider: String, url: String) {
    val handler = LocalUriHandler.current
    var error by remember(url) { mutableStateOf(false) }
    TextButton(enabled = MarketplaceContract.safeUrl(provider, url), onClick = { error = runCatching { handler.openUri(url) }.isFailure }) { Text("Open on ${MarketplaceContract.names[provider] ?: "site"}") }
    if (error) Text("No app could open this link.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketplaceSettings(config: UniversalMarketplaceSettings, save: (UniversalMarketplaceSettings) -> Unit, onDelete: () -> Unit) {
    var sources by remember(config) { mutableStateOf(config.enabledSources.toSet()) }
    var country by remember(config) { mutableStateOf(config.country) }
    var city by remember(config) { mutableStateOf(config.city) }
    var currency by remember(config) { mutableStateOf(config.currency) }
    var count by remember(config) { mutableStateOf(config.maxResults.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sources", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketplaceContract.providers.forEach { id -> FilterChip(id in sources, { sources = if (id in sources) sources - id else sources + id }, label = { Text(MarketplaceContract.names.getValue(id)) }) }
        }
        Text("External searches only. Automatic API access, listing details and monitoring are not configured.")
        Text("Search", style = MaterialTheme.typography.titleMedium)
        Choices("Country", MarketplaceContract.countries, country) { country = it }
        OutlinedTextField(city, { city = it }, label = { Text("Default city") })
        Choices("Currency", MarketplaceContract.currencies, currency) { currency = it }
        OutlinedTextField(count, { count = it }, label = { Text("Result preference · 1–100") }, singleLine = true)
        Text("AI assistance", style = MaterialTheme.typography.titleMedium)
        Text("This workspace uses no AI. Chat profiles can prepare the same searches when you grant plugin access.")
        Text("Privacy & advanced", style = MaterialTheme.typography.titleMedium)
        Text("No location tracking, provider cache, cookies, gateway or background alerts. Saved searches and bookmarks stay in the app and can be included in backups.")
        Button(onClick = { save(UniversalMarketplaceSettings(sources.toList(), country, city, currency, count.toInt())) }, enabled = count.toIntOrNull() in 1..100 && city.length <= 100 && city.none(Char::isISOControl)) { Text("Save settings") }
        TextButton(onClick = onDelete) { Text("Delete saved searches and bookmarks") }
    }
}
