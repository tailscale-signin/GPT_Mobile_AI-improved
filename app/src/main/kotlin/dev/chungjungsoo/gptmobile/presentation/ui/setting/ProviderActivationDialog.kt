package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy

@Composable
internal fun ProviderActivationDialog(profile: PlatformV2, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val policy = ProviderPrivacyPolicy.forProfile(profile) ?: return
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (policy.provider.isRetired) "Provider removed" else "Enable ${policy.provider.displayName}?") },
        text = {
            Column {
                Text(if (policy.provider.isRetired) policy.provider.unavailableReason.orEmpty() else policy.disclosure)
                Text(profile.model)
                if (!policy.provider.isRetired) {
                    Text("Personal tools need separate permission.")
                    TextButton(onClick = { uri.openUri(policy.policyUrl) }) { Text("Privacy policy") }
                }
            }
        },
        confirmButton = { if (!policy.provider.isRetired) TextButton(onClick = onAccept) { Text("Accept and enable") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
