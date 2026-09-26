package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.data.model.SystemPromptPresets

@Composable
fun SystemPromptEditor(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selected = SystemPromptPresets.entries.firstOrNull { it.prompt == value }
    Column(modifier) {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Psychology, null)
                Text(selected?.name ?: "Custom prompt", Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, "Choose expert preset")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Custom · keep editing") }, onClick = { expanded = false })
                SystemPromptPresets.entries.forEach { preset ->
                    DropdownMenuItem(text = {
                        Column {
                            Text(preset.name)
                            Text(preset.summary, style = MaterialTheme.typography.labelSmall)
                        }
                    }, onClick = { onValueChange(preset.prompt); expanded = false })
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("System prompt") },
            supportingText = { Text("Choose a starting point, then make it your own.") },
            minLines = 4,
            maxLines = 12
        )
    }
}
