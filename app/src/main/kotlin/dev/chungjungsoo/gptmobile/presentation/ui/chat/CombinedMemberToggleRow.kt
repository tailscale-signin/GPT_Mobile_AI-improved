package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CombinedMemberToggleRow(
    platformUid: String,
    platformName: String,
    isMember: Boolean,
    isTemporarilyEnabled: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!isMember) }
            .testTag("combined_member_row_$platformUid")
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(platformName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(
            if (!isTemporarilyEnabled) {
                "Paused"
            } else if (isMember) {
                "Added"
            } else {
                "Available"
            },
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            modifier = Modifier.testTag("combined_member_$platformUid"),
            checked = isMember,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
