package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope

@Composable
internal fun MultiSearchResearchPanel(
    selection: MultiSearchResearchSelection,
    defaults: ModelDelegationSettings,
    unlockReason: String?,
    gateways: Map<String, String>,
    onChange: (MultiSearchResearchSelection) -> Unit,
    onConsent: () -> Unit,
    onSaveDefault: () -> Unit
) {
    var consent by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf(false) }
    var draft by remember(selection.depth) { mutableFloatStateOf(selection.depth.toFloat()) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Multi Search · Research", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(selection.researchToggle, { value ->
                    onChange(selection.copy(researchToggle = value))
                    if (value && unlockReason != null) consent = true
                })
            }
            Text("Research Depth · ${draft.toInt()} / 100", style = MaterialTheme.typography.labelLarge)
            Slider(draft, { draft = it }, valueRange = 0f..100f, steps = 99, onValueChangeFinished = { onChange(selection.copy(depth = draft.toInt())) })
            ResearchContentScope.entries.forEach { scope ->
                FilterChip(selection.scope == scope, { onChange(selection.copy(scope = scope)) }, label = {
                    Text(
                        when (scope) {
                            ResearchContentScope.WEB_AND_PAPERS -> "Web + Papers"
                            ResearchContentScope.PAPERS_ONLY -> "Papers only"
                            ResearchContentScope.WEB_ONLY -> "Web only"
                        }
                    )
                })
            }
            val effective = selection.applyTo(defaults).deepResearch
            Text(unlockReason ?: "Unlocked · ${selection.intensity()?.name?.lowercase() ?: "standard"}", color = if (unlockReason == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Up to 3 sources per query · ${effective.maxRounds} rounds · up to ${effective.maxPages} pages. Limits and source availability apply.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { consent = true }) { Text("Academic permissions") }
                TextButton(onClick = onSaveDefault) { Text("Save profile default") }
            }
            TextButton(onClick = { providers = !providers }) { Text("Providers & gateway") }
            if (providers) {
                Text("Native: OpenAlex, Crossref, arXiv, Europe PMC (including PubMed), DataCite. No research-provider account is required. Anonymous quotas apply.", style = MaterialTheme.typography.bodySmall)
                FilterChip(selection.gatewayConnectionUid == null, { onChange(selection.copy(gatewayConnectionUid = null)) }, label = { Text("Native") })
                gateways.forEach { (uid, name) -> FilterChip(selection.gatewayConnectionUid == uid, { onChange(selection.copy(gatewayConnectionUid = uid)) }, label = { Text("Gateway MCP · $name") }) }
                if (gateways.isEmpty()) Text("For PDF reading, add Deep Research · Academic MCP Gateway in Plugins & Tools with your private endpoint and bearer token.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow native fallback if gateway fails", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(selection.allowNativeFallback, { onChange(selection.copy(allowNativeFallback = it)) })
                }
                listOf("openalex", "crossref", "arxiv", "europepmc", "datacite", "opencitations").forEach { id ->
                    val enabled = selection.enabledProviders.isEmpty() || id in selection.enabledProviders
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(id, Modifier.weight(1f))
                        Switch(enabled, { checked ->
                            val current = selection.enabledProviders.ifEmpty { setOf("openalex", "crossref", "arxiv", "europepmc", "datacite", "opencitations") }
                            // Explicit none stays distinct from the default source mask.
                            onChange(selection.copy(enabledProviders = (if (checked) current + id else current - id).ifEmpty { setOf("none") }))
                        })
                    }
                }
                Text("Metadata is a discovery lead. Quotes are verified only against text actually read. Android cannot parse arbitrary PDFs. Moving controls never starts a request.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (consent) {
        AlertDialog(onDismissRequest = { consent = false }, title = { Text("Enable academic research?") }, text = { Text("Allow this AI profile to send research queries to selected public academic providers and the selected gateway. This enables Deep Research and Papers and studies. Existing network restrictions still apply. Sources impose quotas; paywalled text is unavailable. Your gateway needs its own private bearer token.") }, confirmButton = {
            TextButton(onClick = {
                onConsent()
                consent = false
            }) { Text("Enable for this profile") }
        }, dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } })
    }
}
