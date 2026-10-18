package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.nvidia.NvidiaModelCapabilities
import dev.chungjungsoo.gptmobile.data.nvidia.NvidiaReasoningControl
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog

@Composable
fun NvidiaModelPickerDialog(
    apiUrl: String,
    apiKey: String,
    connectionUid: String?,
    currentModel: String,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
    viewModel: NvidiaModelPickerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable(currentModel) { mutableStateOf(currentModel) }
    LaunchedEffect(apiUrl, apiKey, connectionUid) {
        viewModel.load(apiUrl, apiKey, connectionUid)
    }

    // The suggested IDs are not evidence of model availability to this account.
    val suggestions = NvidiaModelCapabilities.suggestedModelIds.map { ProfileModelOption(it, it) }
    val candidates = if (state.live) state.models else suggestions
    val matching = candidates.filter {
        it.id.contains(search.trim(), ignoreCase = true) || it.name.contains(search.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NVIDIA models") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.live) "Models reported by this connection (inference access is not yet verified)." else "Suggested model IDs (availability not verified).")
                if (state.loading) CircularProgressIndicator()
                state.error?.let { Text(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search models") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(
                        enabled = !state.loading,
                        onClick = { viewModel.load(apiUrl, apiKey, connectionUid, forceRefresh = true) }
                    ) { Text("Refresh") }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(matching, key = { it.id }) { option ->
                        val capabilities = NvidiaModelCapabilities.forModel(option.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selection = option.id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selection == option.id, onClick = { selection = option.id })
                            Column(Modifier.weight(1f)) {
                                Text(option.name)
                                if (option.id != option.name) Text(option.id)
                                if (capabilities.reasoningControl != NvidiaReasoningControl.UNKNOWN) {
                                    Text("Reasoning controls documented · Tools: ${if (capabilities.supportsTools == true) "supported" else "unverified"}")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                OutlinedTextField(
                    value = selection,
                    onValueChange = { selection = it },
                    label = { Text("Model ID (manual entry allowed)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selection.isNotBlank(),
                onClick = {
                    onModelSelected(selection.trim())
                    onDismiss()
                }
            ) { Text("Use model") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
