package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyCategory
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class PrivacyModeViewModel @Inject constructor(private val store: PrivacyModeStore) : ViewModel() {
    val state = store.state
    internal val authority get() = store.verifier.authority

    fun setEnabled(enabled: Boolean) = store.setEnabled(enabled)

    suspend fun privateTerms() = store.privateTerms().joinToString("\n")
    suspend fun saveTerms(value: String) = store.savePrivateTerms(value)

    fun setCategory(category: PrivacyCategory, enabled: Boolean) = store.setCategory(category, enabled)
}

/** Simple master switch; maximum safeguards when enabled, individual categories default ON. */
@Composable
internal fun PrivacyModePanel(viewModel: PrivacyModeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Privacy Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.enabled) "ON · Strict privacy filtering" else "OFF · Standard profile availability",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
                modifier = Modifier.testTag("settings_privacy_mode")
            )
        }
        Text(
            "Privacy Mode checks the selected model before sending text. A private Gateway uses its installed model. Cloud profiles become available only after their account and route requirements are verified.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Information to protect", style = MaterialTheme.typography.titleSmall)
        PrivacyCategory.entries.forEach { category ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(category.title, style = MaterialTheme.typography.bodyMedium)
                    Text(category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = category in state.categories,
                    onCheckedChange = { viewModel.setCategory(category, it) },
                    modifier = Modifier.testTag("privacy_category_${category.name}")
                )
            }
        }
        var terms by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(viewModel) {
            try {
                terms = viewModel.privateTerms()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = terms,
            onValueChange = {
                if (it.length <= 8192) terms = it
                saved = false
            },
            label = { Text("Custom private terms (one per line)") },
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.material3.TextButton(onClick = {
            scope.launch {
                try {
                    viewModel.saveTerms(terms)
                    saved = true
                    error = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = true
                }
            }
        }) { Text(if (saved) "Terms saved" else "Save private terms") }
        if (error) Text("Could not access the encrypted private terms.")
        Text("AI Provider Protection", style = MaterialTheme.typography.titleMedium)
        Text("Required whenever Privacy Mode is on", style = MaterialTheme.typography.labelMedium)
        Text("• Zero Data Retention for prompt and response content\n• No training or routine safety retention\n• Content logging disabled\n• Exact provider route, with no fallback\n• Separate verification for external tools and voice")
        Text("Available means the checks passed. Not verified means evidence is missing or a check could not finish. Incompatible means an observed route or setting conflicts with the privacy requirements. This does not change your saved profile settings.", style = MaterialTheme.typography.bodySmall)
        Text("Automated masking recognizes common formats and your custom terms. Provider agreements may allow legally required exceptions and non-content usage metadata.", style = MaterialTheme.typography.bodySmall)
        Text("OpenRouter checks the selected model and its exact upstream endpoints automatically. Current account evidence is still needed for prompt logging, broadcast and default plugins. Other cloud providers require their applicable ZDR account and endpoint approvals. Recheck profiles in New Chat after changing settings.", style = MaterialTheme.typography.bodySmall)
        ProviderVerificationSetup(viewModel.authority)
        val handler = androidx.compose.ui.platform.LocalUriHandler.current
        androidx.compose.material3.TextButton(onClick = { handler.openUri("https://openrouter.ai/settings/privacy") }) { Text("Configure OpenRouter privacy") }
    }
}
