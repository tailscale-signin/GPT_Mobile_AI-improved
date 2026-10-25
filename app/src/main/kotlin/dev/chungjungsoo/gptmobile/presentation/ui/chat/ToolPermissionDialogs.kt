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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataGrantOptions
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
    onAccept: (PersonalDataGrantOptions) -> Unit,
    onDismiss: () -> Unit
) {
    val policy = request.profileSnapshot?.let(dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy::forProfile)
    var rememberProfile by remember(request) { mutableStateOf(false) }
    var healthRangeDays by remember(request) { mutableStateOf(7) }
    var preciseLocation by remember(request) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share personal data with this provider?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${request.profileName} may use ${request.toolName} and send its arguments and returned data to ${policy?.recipients?.joinToString() ?: request.profileName}.")
                Text(policy?.disclosure ?: "Retention of prompts and tool results has not been verified.")
                when (request.dataCategory) {
                    PersonalDataCategory.HEALTH -> {
                        Text("Health records to share", style = MaterialTheme.typography.titleSmall)
                        listOf(7, 30).forEach { days ->
                            Row(Modifier.fillMaxWidth().selectable(selected = healthRangeDays == days, role = Role.RadioButton, onClick = { healthRangeDays = days }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = healthRangeDays == days, onClick = null)
                                Text("Last $days calendar days")
                            }
                        }
                        Text("The range includes today in your phone's time zone and is checked for every read. Existing Health Connect permissions still apply.", style = MaterialTheme.typography.bodySmall)
                    }
                    PersonalDataCategory.PRECISE_LOCATION, PersonalDataCategory.COARSE_LOCATION -> {
                        Text("Location detail to share", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth().selectable(selected = !preciseLocation, role = Role.RadioButton, onClick = { preciseLocation = false }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !preciseLocation, onClick = null)
                            Text("Approximate area · 0.1° coordinate grid")
                        }
                        Row(Modifier.fillMaxWidth().selectable(selected = preciseLocation, role = Role.RadioButton, onClick = { preciseLocation = true }), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = preciseLocation, onClick = null)
                            Text("Precise coordinates and altitude")
                        }
                        Text("This permission covers the AI recipient. Nearby map searches also need separate permission for OpenStreetMap / Overpass.", style = MaterialTheme.typography.bodySmall)
                    }
                    PersonalDataCategory.MEMORY -> Text("Saved memory retrieved by this tool may be shared. Other personal tools need their own permission.")
                    PersonalDataCategory.PRIVATE_CONTENT -> Text("Files and private connected content returned by this tool may be shared. Other tools need their own permission.")
                    PersonalDataCategory.UNCLASSIFIED -> Text("This tool's data categories have not been verified. Its results may contain personal or confidential information; this permission applies only to this tool.")
                }
                Text(if (rememberProfile) "This profile may use the selected tool across conversations for 30 days. Revoke access in Plugins & Tools." else "Only this conversation may use the selected tool for the next 24 hours. Revoke access in Plugins & Tools.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberProfile, onCheckedChange = { rememberProfile = it })
                    Text("Remember this profile and tool for 30 days", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { FilledTonalButton(onClick = { onAccept(PersonalDataGrantOptions(rememberProfile, healthRangeDays, preciseLocation)) }, enabled = policy?.personalDataAllowed == true) { Text(if (rememberProfile) "Allow for this profile" else "Allow for this chat") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Keep personal tools off") } }
    )
}
