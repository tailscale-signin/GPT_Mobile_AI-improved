package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppEmptyState
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppShapes

@Composable
internal fun SettingsDiscoveryPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    entries: List<SettingsSearchEntry>,
    onSelect: (SettingsSearchEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query,
            onQueryChange,
            label = { Text(stringResource(R.string.settings_search_all)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
            },
            singleLine = true,
            shape = AppShapes.input,
            modifier = Modifier.fillMaxWidth().testTag("settings_search")
        )
        if (query.isNotBlank()) {
            val matches = SettingsSearchRegistry.search(query, entries)
            if (matches.isEmpty()) AppEmptyState(stringResource(R.string.settings_search_empty))
            matches.forEach { entry ->
                TextButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth().testTag("setting_result_${entry.key}")) {
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.breadcrumb, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }
        }
    }
}
