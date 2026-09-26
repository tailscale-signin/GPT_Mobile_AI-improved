package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption
import kotlinx.coroutines.CancellationException

@Composable
fun CloudModelPickerDialog(
    profileUid: String,
    loadModels: suspend (String) -> List<ProfileModelOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<ProfileModelOption>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileUid, refresh) {
        loading = true
        error = null
        try {
            models = loadModels(profileUid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load models. You can still enter a model ID."
        } finally {
            loading = false
        }
    }
    val filtered = remember(query, models) { models.filter { it.name.contains(query, true) || it.id.contains(query, true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a model") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search provider models") }, singleLine = true)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
                if (!loading && error == null && filtered.isEmpty()) Text("No matching models", modifier = Modifier.padding(12.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                onSelect(model.id)
                                onDismiss()
                            }.padding(vertical = 12.dp)
                        ) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            if (model.id != model.name) Text(model.id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
