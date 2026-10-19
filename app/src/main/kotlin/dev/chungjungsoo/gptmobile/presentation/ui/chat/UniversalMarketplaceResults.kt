package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.agent.displayResult
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceContract
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchAction
import dev.chungjungsoo.gptmobile.data.universalmarketplace.MarketplaceSearchResult
import dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace.MarketplaceActionCard
import dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace.MarketplaceActionDetails
import dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace.UniversalMarketplaceScreen

internal fun marketplaceResults(events: List<ToolEvent>): List<MarketplaceSearchResult> = events.filter {
    !it.isError && it.modelToolName == MarketplaceContract.TOOL
}.mapNotNull { event ->
    val raw = event.displayResult()?.takeIf { it.length <= 64_000 } ?: return@mapNotNull null
    runCatching { MarketplaceContract.json.decodeFromString(MarketplaceSearchResult.serializer(), raw).validated() }.getOrNull()
}.distinctBy { it.requestId }

@Composable
internal fun UniversalMarketplaceResults(events: List<ToolEvent>) {
    val results = remember(events) { marketplaceResults(events) }
    var detail by remember { mutableStateOf<MarketplaceSearchAction?>(null) }
    var workspace by remember { mutableStateOf<MarketplaceSearchResult?>(null) }
    results.forEach { result ->
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Marketplace · ${result.request.query}", style = MaterialTheme.typography.titleMedium)
            Text("No listings retrieved. Searches ready on ${result.searchActions.size} sites.")
            result.providerStatuses.forEach { status -> Text("${MarketplaceContract.names[status.provider]}: ${status.state.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }
            result.searchActions.forEach { action -> MarketplaceActionCard(action) { detail = action } }
            TextButton(onClick = { workspace = result }) { Text("Open Marketplace workspace") }
        }
    }
    detail?.let { MarketplaceActionDetails(it, onDismiss = { detail = null }) }
    workspace?.let { result ->
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { workspace = null }, fullScreenScene = true, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            UniversalMarketplaceScreen(onBack = { workspace = null }, initialRequest = result.request)
        }
    }
}
