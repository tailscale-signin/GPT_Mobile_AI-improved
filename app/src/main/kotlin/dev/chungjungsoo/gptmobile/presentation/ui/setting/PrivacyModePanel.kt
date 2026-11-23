package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyCategory
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PrivacyModeViewModel @Inject constructor(
    private val store: PrivacyModeStore,
    settings: SettingRepository
) : ViewModel() {
    val state = store.state
    val updates = store.updates
    internal val authority get() = store.verifier.authority
    internal val setup get() = store.verifier.accountSetup
    internal val openRouterProfiles = settings.observePlatformV2s().map { profiles ->
        profiles.filter {
            it.enabled &&
                it.compatibleType == ClientType.OPENROUTER &&
                it.apiUrl.trimEnd('/') in OpenRouterIdentity.endpoints &&
                !it.token.isNullOrBlank()
        }.distinctBy { it.apiUrl.trimEnd('/') to it.token }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(enabled: Boolean) = store.requestEnabled(enabled)

    fun retrySaving() = store.retrySaving()

    suspend fun privateTerms() = withContext(Dispatchers.IO) { store.privateTerms().joinToString("\n") }
    suspend fun saveTerms(value: String) = withContext(Dispatchers.IO) { store.savePrivateTerms(value) }

    fun setCategory(category: PrivacyCategory, enabled: Boolean) = store.requestCategory(category, enabled)
}

/** Availability for new private conversations; saved conversation locks remain permanent. */
@Composable
internal fun PrivacyModePanel(viewModel: PrivacyModeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val profiles by viewModel.openRouterProfiles.collectAsStateWithLifecycle()
    var customize by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Privacy Mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.enabled) "Available for new private chats" else "Unavailable for new private chats",
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
        if (updates.saving) Text("Saving privacy settings…", style = MaterialTheme.typography.bodySmall)
        if (updates.failed) {
            Text("Privacy settings could not be saved. Review the current choices and retry.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::retrySaving, enabled = !updates.saving, modifier = Modifier.testTag("privacy_settings_retry")) {
                Text("Retry saving")
            }
        }
        Text(
            "Enable this option to make Private chat available in New Chat. Select it there to permanently enable that conversation's privacy policy for every selected AI profile. New chats start unchecked. Turning this option off only prevents new private chats; it does not unlock saved private chats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "All enabled AI profiles remain selectable. Detected details in your chosen categories are replaced on this device before a cloud request. The remaining text can reach the selected cloud model. Route verification is separate: an unverified provider's retention is unknown and its own policies apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "The lock marks the conversation's permanent policy. Yellow text marks confirmed replacements or local restoration for that message; it does not mean every sensitive detail was detected or that the whole conversation stayed on this device. Review custom terms when a detail needs explicit protection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("privacy_guarantees_explained")
        )
        ProviderVerificationSetup(viewModel.authority, viewModel.setup, profiles)
        TextButton(onClick = { customize = !customize }, modifier = Modifier.testTag("privacy_customize")) {
            Text(if (customize) "Hide custom protections" else "Customize protections")
        }
        if (customize) {
            Text("Information to protect", style = MaterialTheme.typography.titleSmall)
            Text("All categories are protected by default. Add names or other details as custom private terms.", style = MaterialTheme.typography.bodySmall)
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
            PrivateTermsEditor(viewModel)
        }
        TextButton(onClick = { details = !details }) {
            Text(if (details) "Hide protection details" else "How protection works")
        }
        if (details) {
            Text("A verified strict cloud route requires its account, model and upstream checks, including no routine content retention. When those checks are unavailable, the selected profile can use locally masked text; the app does not claim verified retention for that route. External tools, attachments and voice require their own destination checks.", style = MaterialTheme.typography.bodySmall)
            Text("Enabled calculator, current-date and text-slicing tools run on this device. Their results pass through the same personal-data permissions and local masking before reaching a model. GitHub, Research and connected service tools remain blocked until their external routes meet the privacy requirements.", style = MaterialTheme.typography.bodySmall)
            Text("Route verification reports only the checks it actually passed. Not verified means evidence is incomplete; incompatible means a strict-route requirement is unmet. These labels do not disable profile selection or prove that automated masking found every private detail.", style = MaterialTheme.typography.bodySmall)
            Text("Automated masking recognizes common formats and your custom terms. Provider agreements may allow legally required exceptions and non-content usage metadata. Other cloud providers require their applicable account and endpoint approvals.", style = MaterialTheme.typography.bodySmall)
            Text("Diagnostic exports omit private text and thoughts by default. Full forensic export is a separate choice with a local preview and may contain originals even for a locked private chat.", style = MaterialTheme.typography.bodySmall)
        }
        PrivacyBenchmarkPanel()
    }
}

@Composable
private fun PrivateTermsEditor(viewModel: PrivacyModeViewModel) {
    var terms by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        try {
            terms = viewModel.privateTerms()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }
    OutlinedTextField(
        value = terms,
        onValueChange = {
            if (it.length <= 8192) terms = it
            saved = false
        },
        enabled = !loading && !saving,
        label = { Text("Custom private terms (one per line)") },
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(enabled = !loading && !saving, onClick = {
        if (!saving) {
            saving = true
            val value = terms
            scope.launch {
                try {
                    viewModel.saveTerms(value)
                    saved = true
                    error = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = true
                } finally {
                    saving = false
                }
            }
        }
    }) { Text(if (saved) "Terms saved" else "Save private terms") }
    if (error) Text("Could not access the encrypted private terms.", color = MaterialTheme.colorScheme.error)
}
