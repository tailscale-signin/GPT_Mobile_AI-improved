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
import androidx.compose.runtime.getValue
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

@HiltViewModel
class PrivacyModeViewModel @Inject constructor(private val store: PrivacyModeStore) : ViewModel() {
    val state = store.state

    fun setEnabled(enabled: Boolean) = store.setEnabled(enabled)

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
            "Maximum protection has no strength slider. Unverified cloud and free model routes are locked while enabled. " +
                "Local inference and a private self-hosted gateway do not require third-party retention checks. " +
                "Cloud prompt anonymization, account verification and tool-path enforcement are still being integrated; " +
                "this initial gate does not certify any remote endpoint.",
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
    }
}
