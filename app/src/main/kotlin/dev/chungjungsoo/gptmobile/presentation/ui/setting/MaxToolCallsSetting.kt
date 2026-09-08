package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.SettingItem

/**
 * Platform preference and editor for the maximum number of tool calls in one agent run.
 * A blank value maps to [Int.MAX_VALUE], which represents an unlimited run.
 */
@Composable
fun MaxToolCallsSetting(
    maxToolCalls: Int,
    enabled: Boolean,
    onMaxToolCallsChanged: (Int) -> Unit
) {
    var dialogOpen by remember { mutableStateOf(false) }

    SettingItem(
        modifier = Modifier.height(64.dp),
        title = stringResource(R.string.maximum_tool_calls),
        description = if (maxToolCalls == Int.MAX_VALUE) {
            stringResource(R.string.unlimited)
        } else {
            maxToolCalls.toString()
        },
        enabled = enabled,
        onItemClick = { dialogOpen = true },
        showTrailingIcon = false,
        showLeadingIcon = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Numbers,
                contentDescription = stringResource(R.string.maximum_tool_calls)
            )
        }
    )

    if (dialogOpen) {
        MaxToolCallsDialog(
            maxToolCalls = maxToolCalls,
            onDismissRequest = { dialogOpen = false },
            onConfirmRequest = { value ->
                onMaxToolCallsChanged(value)
                dialogOpen = false
            }
        )
    }
}

@Composable
private fun MaxToolCallsDialog(
    maxToolCalls: Int,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Int) -> Unit
) {
    var input by remember(maxToolCalls) {
        mutableStateOf(if (maxToolCalls == Int.MAX_VALUE) "" else maxToolCalls.toString())
    }
    val normalizedInput = input.trim()
    val parsedValue = normalizedInput.toIntOrNull()
    val isUnlimited = normalizedInput.isEmpty()
    val isValid = isUnlimited || (parsedValue != null && parsedValue > 0)

    AlertDialog(
        title = { Text(stringResource(R.string.maximum_tool_calls_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.maximum_tool_calls_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.maximum_tool_calls_label)) },
                    placeholder = { Text(stringResource(R.string.unlimited)) },
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    supportingText = {
                        Text(
                            stringResource(
                                if (isValid) {
                                    R.string.maximum_tool_calls_blank_hint
                                } else {
                                    R.string.maximum_tool_calls_invalid
                                }
                            )
                        )
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onConfirmRequest(if (isUnlimited) Int.MAX_VALUE else checkNotNull(parsedValue))
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
