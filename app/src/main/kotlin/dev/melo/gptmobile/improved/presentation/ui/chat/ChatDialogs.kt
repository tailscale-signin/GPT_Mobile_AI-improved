package dev.melo.gptmobile.improved.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.database.entity.ClientTypeV2
import dev.melo.gptmobile.improved.data.model.LocalModelInfo

@Composable
fun ChatTitleDialog(
    initialTitle: String,
    onDefaultTitleMode: () -> Unit,
    onConfirmRequest: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.update_chat_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDefaultTitleMode,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.use_default_title))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmRequest(title)
                    onDismissRequest()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelDialog(
    platformOrder: List<String>,
    initialModels: Map<String, String>,
    platformNames: Map<String, String>,
    platformClientTypes: Map<String, ClientTypeV2>,
    downloadedLocalModels: List<LocalModelInfo> = emptyList(),
    onNavigateToLocalModels: () -> Unit = {},
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Map<String, String>) -> Unit
) {
    val selectedModels = remember { mutableStateOf(initialModels.toMutableMap()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.chat_models)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                platformOrder.forEach { platformUid ->
                    val platformName = platformNames[platformUid] ?: platformUid
                    val clientType = platformClientTypes[platformUid]
                    val currentModel = selectedModels.value[platformUid].orEmpty()

                    Column {
                        Text(
                            text = platformName,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        if (clientType == ClientTypeV2.LOCAL && downloadedLocalModels.isNotEmpty()) {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it }
                            ) {
                                OutlinedTextField(
                                    value = currentModel,
                                    onValueChange = { newModel ->
                                        selectedModels.value = selectedModels.value.toMutableMap().apply {
                                            put(platformUid, newModel)
                                        }
                                    },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth(),
                                    readOnly = false
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    downloadedLocalModels.forEach { localModel ->
                                        DropdownMenuItem(
                                            text = { Text(localModel.name) },
                                            onClick = {
                                                selectedModels.value = selectedModels.value.toMutableMap().apply {
                                                    put(platformUid, localModel.name)
                                                }
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = currentModel,
                                onValueChange = { newModel ->
                                    selectedModels.value = selectedModels.value.toMutableMap().apply {
                                        put(platformUid, newModel)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmRequest(selectedModels.value)
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

@Composable
fun UserMessageEditDialog(
    initialQuestion: String,
    attachments: List<String> = emptyList(),
    onFileSelected: (String) -> Unit = {},
    onCopyFailed: () -> Unit = {},
    onFileRemoved: (String) -> Unit = {},
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialQuestion) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(text) }
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

@Composable
fun AssistantMessageEditDialog(
    initialMessage: String,
    attachments: List<String> = emptyList(),
    onFileSelected: (String) -> Unit = {},
    onCopyFailed: () -> Unit = {},
    onFileRemoved: (String) -> Unit = {},
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String, String) -> Unit
) {
    var text by remember { mutableStateOf(initialMessage) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(text, "") }
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
