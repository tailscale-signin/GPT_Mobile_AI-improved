package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings

@Composable
internal fun SettingsDiscoveryPanel(
    settings: AppFeatureSettings,
    destinations: Map<String, () -> Unit>,
    change: (AppFeature, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Search All Settings") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (query.isNotBlank()) {
            val features = AppFeature.entries.filter { "${it.title} ${it.description}".contains(query, true) }
            val sections = if (query.isBlank()) emptyMap() else destinations.filterKeys { it.contains(query, true) }
            if (features.isEmpty() && sections.isEmpty()) Text("No Matching Settings")
            features.forEach { feature ->
                Card {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(feature.title)
                            Text(feature.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.withFeature(feature, true) == settings, { change(feature, it) })
                    }
                }
            }
            sections.forEach { (title, action) -> TextButton(onClick = action) { Text(title) } }
        }
    }
}
