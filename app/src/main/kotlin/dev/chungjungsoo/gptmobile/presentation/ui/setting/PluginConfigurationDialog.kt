package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun PluginConfigurationDialog(
    id: String,
    name: String,
    features: AppFeatureSettings,
    onFeature: (AppFeature, Boolean) -> Unit,
    onSave: (PluginExecutionSettings) -> Unit,
    onConnection: () -> Unit,
    onDismiss: () -> Unit,
    onRevokePermissions: () -> Unit = {},
    isAmazon: Boolean = id == ToolPluginId.AMAZON_SEARCH
) {
    var config by remember(id) { mutableStateOf(features.pluginExecution[id] ?: (if (isAmazon) features.pluginExecution[ToolPluginId.AMAZON_SEARCH] else null) ?: PluginExecutionSettings()) }
    var options by remember(id) { mutableStateOf(features) }
    var marketplaceMenu by remember(id) { mutableStateOf(false) }
    val nativeAmazon = id == ToolPluginId.AMAZON_FREE
    val validZone = config.timeZone.isBlank() || runCatching { java.time.ZoneId.of(config.timeZone) }.isSuccess
    AlertDialog(
        icon = { Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary) },
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsHero("Plugin controls", name, "")
                if (isAmazon || nativeAmazon) {
                    SettingsPanel("Amazon products") {
                        Column {
                            TextButton(onClick = { marketplaceMenu = true }) { Text("Marketplace · ${config.amazonMarketplace}") }
                            Text("Only this marketplace is used, including when an AI requests another country.", style = MaterialTheme.typography.bodySmall)
                            DropdownMenu(expanded = marketplaceMenu, onDismissRequest = { marketplaceMenu = false }) {
                                val markets = if (nativeAmazon) dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket.entries.associate { it.domain to it.label } else AmazonProducts.marketplaces
                                markets.forEach { (domain, country) ->
                                    DropdownMenuItem(text = { Text("$country · $domain") }, onClick = {
                                        config = config.copy(amazonMarketplace = domain)
                                        marketplaceMenu = false
                                    })
                                }
                            }
                        }
                        PluginSlider("Products per search", config.searchResults, 1..10) { config = config.copy(searchResults = it) }
                        PluginSwitch("Include sponsored products", config.amazonIncludeSponsored) { config = config.copy(amazonIncludeSponsored = it) }
                        if (nativeAmazon) {
                            PluginSlider("Daily requests", config.amazonDailyRequests, 1..100) { config = config.copy(amazonDailyRequests = it) }
                            Text("No API key required. Every lookup reads a public page; failed attempts and redirects count toward your UTC daily allowance. Requests are spaced by at least five seconds. Blocked pages enter a cooldown. Prices and availability may change at checkout.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            PluginSwitch("Always request fresh prices", config.amazonFreshPrices) { config = config.copy(amazonFreshPrices = it) }
                            Text("SerpApi searches use your provider allowance. Fresh requests bypass its cache. Prices and availability may change at checkout.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onConnection) { Text("API key & connection test") }
                        }
                    }
                }
                if (id == ToolPluginId.NEWS) {
                    SettingsPanel("News region") {
                        OutlinedTextField(config.newsCountry, { config = config.copy(newsCountry = it.take(2)) }, label = { Text("Country · CA, US, GB") }, singleLine = true)
                        OutlinedTextField(config.newsLanguage, { config = config.copy(newsLanguage = it.take(5)) }, label = { Text("Language · en, fr") }, singleLine = true)
                        Text("Google News, Trends and Hacker News need no key. The optional Google News SerpApi backend needs its key on your MCP host.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onConnection) { Text("MCP connections") }
                    }
                }
                if (id == ToolPluginId.AIRBNB || id == ToolPluginId.GOOGLE_PLACES) {
                    SettingsPanel("Connections") {
                        Text(if (id == ToolPluginId.AIRBNB) "Choose Airbnb · OpenBnB from Marketplace and sign in, or connect your own Airbnb MCP host. Prices and fees depend on dates and guests; missing data stays unknown." else "Install Google Places from Marketplace and enter a Places API key, or connect Google Maps Grounding MCP with its API key. Google account billing and API restrictions apply.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onConnection) { Text("Manage connections") }
                    }
                }
                if (id == ToolPluginId.GITHUB) {
                    SettingsPanel("Speed & efficiency") {
                        PluginSwitch("Conditional requests", options.githubConditionalReads) { options = options.copy(githubConditionalReads = it) }
                        PluginSwitch("Cache files by content hash", options.githubBlobCache) { options = options.copy(githubBlobCache = it) }
                        PluginSlider("Repository list freshness", config.githubCacheSeconds, 0..120, "s") { config = config.copy(githubCacheSeconds = it) }
                        TextButton(onClick = onConnection) { Text("Account, token & permissions") }
                    }
                }
                if (id == ToolPluginId.WEB_SEARCH) {
                    SettingsPanel("Search") {
                        PluginSlider("Results per engine", config.searchResults, 1..10) { config = config.copy(searchResults = it) }
                        PluginSwitch("Parallel engines", options.parallelSearch) { options = options.copy(parallelSearch = it) }
                        PluginSwitch("Combine duplicate URLs", options.deduplicateSearch) { options = options.copy(deduplicateSearch = it) }
                        Text("Choose engines and crawlers in each model profile.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (id == ToolPluginId.CALCULATOR) {
                    PluginSlider("Displayed decimal places", config.decimalPlaces, 0..15) { config = config.copy(decimalPlaces = it) }
                    Text("The numeric result retains full precision.", style = MaterialTheme.typography.bodySmall)
                }
                if (id == ToolPluginId.CURRENT_DATE) {
                    OutlinedTextField(config.timeZone, { config = config.copy(timeZone = it.trim()) }, label = { Text("Time zone · blank uses device") }, placeholder = { Text("America/Toronto") }, isError = !validZone, singleLine = true)
                }
                if (id == ToolPluginId.DEVICE_LOCATION) {
                    Text("A location fix is shared with enabled models for five minutes, then expires automatically.", style = MaterialTheme.typography.bodySmall)
                    PluginSwitch("Nearby place lookup", config.nearbyPlaces) { config = config.copy(nearbyPlaces = it) }
                    PluginSlider("Maximum search radius", config.nearbyRadiusMeters, 100..5000, "m") { config = config.copy(nearbyRadiusMeters = it) }
                }
                if (id == ToolPluginId.READ_URL) {
                    PluginSwitch("Include page links", config.includePageLinks) { config = config.copy(includePageLinks = it) }
                }
                if (id == ToolPluginId.READ_FILES) {
                    PluginSlider("Maximum lines per excerpt", config.fileExcerptLines, 10..2000) { config = config.copy(fileExcerptLines = it) }
                }
                SettingsPanel(
                    if (id == ToolPluginId.READ_FILES) {
                        "File excerpts"
                    } else if (id == ToolPluginId.READ_URL) {
                        "Page reading"
                    } else {
                        "Execution limits"
                    }
                ) {
                    PluginSlider("Timeout", config.timeoutSeconds, 5..120, "s") { config = config.copy(timeoutSeconds = it) }
                    PluginSlider("Maximum result characters", config.maxOutputCharacters, 1000..128000) { config = config.copy(maxOutputCharacters = it) }
                }
                TextButton(onClick = onRevokePermissions) { Text("Revoke saved permissions · all models") }
                TextButton(onClick = {
                    config = PluginExecutionSettings()
                    options = AppFeatureSettings()
                }) { Text("Recommended defaults") }
            }
        },
        confirmButton = {
            TextButton(enabled = validZone, onClick = {
                if (id == ToolPluginId.GITHUB) {
                    onFeature(AppFeature.GITHUB_CONDITIONAL_READS, options.githubConditionalReads)
                    onFeature(AppFeature.GITHUB_BLOB_CACHE, options.githubBlobCache)
                }
                if (id == ToolPluginId.WEB_SEARCH) {
                    onFeature(AppFeature.PARALLEL_SEARCH, options.parallelSearch)
                    onFeature(AppFeature.DEDUPLICATE_SEARCH, options.deduplicateSearch)
                }
                onSave(config)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PluginSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change)
    }
}

@Composable
private fun PluginSlider(label: String, value: Int, range: IntRange, unit: String = "", change: (Int) -> Unit) {
    Column {
        Text("$label · $value$unit", style = MaterialTheme.typography.labelLarge)
        Slider(value.toFloat(), { change(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}
