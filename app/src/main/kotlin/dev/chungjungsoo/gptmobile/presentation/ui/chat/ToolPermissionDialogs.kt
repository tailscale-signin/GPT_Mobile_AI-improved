package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun ToolApprovalDialog(
    approval: ToolApproval,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllowTool: () -> Unit,
    onAlwaysAllowProvider: () -> Unit,
    onAllowInConversation: () -> Unit = {}
) {
    var allowProvider by remember(approval.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.mcp_brand_protocol),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(28.dp)
                )
            }
        },
        title = { Text("Allow this tool?", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${approval.connection} wants to run ${approval.tool}.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Requested action", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            approval.argumentPreview.ifBlank { "No additional arguments." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Allow once for this action, or remember it for every model. Change this anytime in Plugins/Tools → Permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onAlwaysAllowTool, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Always allow this tool · all models")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowProvider, onCheckedChange = { allowProvider = it })
                    Text("Always allow all tools from ${approval.connection}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = if (allowProvider) onAlwaysAllowProvider else onAllowOnce
            ) {
                Text(if (allowProvider) "Always allow provider" else "Allow once")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDeny) { Text("Deny") } }
    )
}

@Composable
internal fun FreeToolConsentDialog(
    request: ChatViewModel.FreeToolConsentRequest,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val policy = request.profileSnapshot?.let(dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy::forProfile)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share personal data with this provider?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${request.toolName} may send its arguments and returned data to ${policy?.recipients?.joinToString() ?: request.profileName}.")
                Text(policy?.disclosure ?: "Retention of prompts and tool results has not been verified.")
                Text("Permission applies only to this profile, model and tool. Revoke it in Plugins & Tools.")
            }
        },
        confirmButton = { FilledTonalButton(onClick = onAccept) { Text("Allow for this profile") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Keep personal tools off") } }
    )
}
