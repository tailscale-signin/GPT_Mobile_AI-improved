package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon

@Composable
internal fun GitHubWorkspacePicker(
    title: String,
    searchLabel: String,
    choices: List<String>,
    selected: String?,
    loading: Boolean,
    error: String?,
    more: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(choices, query) { choices.distinct().filter { it.contains(query.trim(), ignoreCase = true) } }
    FadingAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(searchLabel) }, leadingIcon = { ThemeIcon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(matches, key = { it }) { name ->
                        DropdownMenuItem(
                            text = { Text(name, color = if (selected == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            trailingIcon = { if (selected == name) ThemeIcon(Icons.Rounded.Check, "Selected") },
                            enabled = !loading,
                            onClick = { onSelect(name) }
                        )
                    }
                    if (matches.isEmpty() && !loading && error == null) {
                        item {
                            val message = when {
                                query.isBlank() -> "No choices available."
                                more -> "No matches on loaded pages."
                                else -> "No matches."
                            }
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (error != null) {
                        item { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (!more && choices.isEmpty()) item { TextButton(onClick = onRetry, enabled = !loading) { Text("Retry") } }
                    }
                    if (more) item { TextButton(onClick = onLoadMore, enabled = !loading) { Text("Load more") } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
