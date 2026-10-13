package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.priceContextKey
import java.time.LocalDate

@Composable
fun NewChatInsightsSettingsPanel(viewModel: ManualPricesViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pricing by viewModel.pricingState.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var uid by remember { mutableStateOf<String?>(null) }
    Column {
        TextButton(onClick = { viewModel.refresh() }, enabled = !pricing.refreshing) { Text(stringResource(R.string.insights_refresh)) }
        pricing.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.insights_manual_manage)) }
    }
    if (open) {
        AlertDialog(onDismissRequest = {
            open = false
            uid = null
        }, title = { Text(stringResource(R.string.insights_manual_manage)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.insights_manual_help))
                profiles.filter { it.compatibleType != ClientType.LITERT_LM }.forEach { profile -> TextButton(onClick = { uid = profile.uid }) { Text("${profile.name} · ${profile.model}") } }
                profiles.firstOrNull { it.uid == uid }?.let { profile ->
                    val existing = settings.manualPrices.lastOrNull { it.contextKey == priceContextKey(profile) }?.price
                    var input by remember(uid) { mutableStateOf(existing?.inputPerMillion.orEmpty()) }
                    var output by remember(uid) { mutableStateOf(existing?.outputPerMillion.orEmpty()) }
                    var currency by remember(uid) { mutableStateOf(existing?.currency ?: "USD") }
                    var source by remember(uid) { mutableStateOf(existing?.sourceUrl.orEmpty()) }
                    var review by remember(uid) { mutableStateOf(existing?.reviewedOn ?: LocalDate.now().toString()) }
                    var expiry by remember(uid) { mutableStateOf(LocalDate.now().plusDays(30).toString()) }
                    OutlinedTextField(input, { input = it }, label = { Text("Input / 1M tokens") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(output, { output = it }, label = { Text("Output / 1M tokens") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(currency, { currency = it }, label = { Text("Billing currency (USD, CAD…)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(source, { source = it }, label = { Text("Source URL or contract note") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(review, { review = it }, label = { Text("Reviewed (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(expiry, { expiry = it }, label = { Text("Expires (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { viewModel.save(profile, input, output, currency, source, review, expiry) }) { Text(stringResource(R.string.insights_manual_save)) }
                    if (existing != null) TextButton(onClick = { viewModel.remove(profile) }) { Text(stringResource(R.string.insights_manual_delete)) }
                    status?.let { Text(it) }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                open = false
                uid = null
            }) { Text(stringResource(R.string.close)) }
        })
    }
}
