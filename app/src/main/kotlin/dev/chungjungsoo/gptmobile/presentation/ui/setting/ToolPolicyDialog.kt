package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.permissions.ToolPolicy

@Composable
internal fun ToolPolicyDialog(connection: ToolConnection, onDismiss: () -> Unit, onResetGrants: () -> Unit = {}, onSave: (String, String) -> Unit) {
    var policy by remember(connection) { mutableStateOf(connection.toolPolicy) }
    var reads by remember(connection) { mutableStateOf(connection.approvedReadTools) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_policy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tool_policy_note))
                ToolPolicy.entries.forEach { value ->
                    FilterChip(selected = policy == value.name, onClick = { policy = value.name }, label = { Text(value.name.lowercase().replace('_', ' ')) })
                }
                TextButton(onClick = onResetGrants) { Text("Reset Always allow permissions") }
                OutlinedTextField(value = reads, onValueChange = { reads = it }, label = { Text(stringResource(R.string.tool_policy_reads)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(policy, reads) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
