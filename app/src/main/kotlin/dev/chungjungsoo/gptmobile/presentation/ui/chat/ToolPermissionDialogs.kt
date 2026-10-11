package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppChoiceSheet as AlertDialog

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
    var grantScope by rememberSaveable(approval.id) { mutableStateOf(0) }
    var showScopes by rememberSaveable(approval.id) { mutableStateOf(false) }
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
                    "Allow once for this action, or remember it for every model. Change this anytime in Tools → Permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showScopes = !showScopes }) { Text(if (showScopes) "Hide permission scope" else "Change permission scope") }
                if (showScopes) {
                    listOf("Only this action", "This tool · all models", "All tools from ${approval.connection}").forEachIndexed { index, label ->
                        Row(Modifier.fillMaxWidth().clickable { grantScope = index }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = grantScope == index, onClick = { grantScope = index })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (grantScope > 0) Text("This permission remains active for future requests. Revoke it in Tools → Permissions.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = when (grantScope) {
                    1 -> onAlwaysAllowTool
                    2 -> onAlwaysAllowProvider
                    else -> onAllowOnce
                }
            ) {
                Text(
                    when (grantScope) {
                        1 -> "Allow tool for all models"
                        2 -> "Allow provider"
                        else -> "Allow once"
                    }
                )
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
    var unlocked by rememberSaveable(request.profileUid, request.toolId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (unlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = if (unlocked) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(30.dp),
                    tint = if (unlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        },
        title = { Text(if (unlocked) "Tool unlocked" else "Unlock external tool data") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${request.profileName} is a free AI profile. Using ${request.toolName} can send the tool request and returned data to that free model provider.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Data tracking is on at the free provider: requests and tool results may be logged under its data policy. You can revoke this permission in Tools → Permissions at any time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { unlocked = !unlocked }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = unlocked, onCheckedChange = { unlocked = it })
                            Text("I understand this data sharing and permission scope.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("The accepted tool permission is remembered for all models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onAccept, enabled = unlocked) {
                Text("Accept & unlock")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Not now") } }
    )
}
