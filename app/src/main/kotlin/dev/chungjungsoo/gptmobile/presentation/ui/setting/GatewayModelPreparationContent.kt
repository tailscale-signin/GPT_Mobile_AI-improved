package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState

@Composable
internal fun GatewayModelPreparationContent(state: GatewayModelPreparationState, onRecheck: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("gateway_model_preparation"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Model: ${state.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.message, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRecheck, enabled = !state.busy) { Text("Recheck") }
    }
}
