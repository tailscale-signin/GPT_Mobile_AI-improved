package com.example.gptmobileai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Data model storing configuration details for an AI platform provider.
 */
data class PlatformConfiguration(
    val platform: AIPlatform,
    val apiKey: String = "",
    val endpointUrl: String = "",
    val defaultModel: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val isEnabled: Boolean = true
)

/**
 * Settings dialog enabling users to inspect and configure provider properties,
 * secrets, and generation hyperparameters.
 */
@Composable
fun PlatformSettingsDialog(
    initialConfig: PlatformConfiguration,
    onSave: (PlatformConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var endpointUrl by remember { mutableStateOf(initialConfig.endpointUrl) }
    var defaultModel by remember { mutableStateOf(initialConfig.defaultModel) }
    var temperature by remember { mutableFloatStateOf(initialConfig.temperature) }
    var isEnabled by remember { mutableStateOf(initialConfig.isEnabled) }
    var showApiKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformLabel(platform = initialConfig.platform, compact = true)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Configuration")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Enabled status toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable Platform", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { isEnabled = it }
                    )
                }

                // API Key input (except local LLM)
                if (initialConfig.platform != AIPlatform.LOCAL_LLM) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key / Bearer Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrect = false
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide API Key" else "Show API Key"
                                )
                            }
                        }
                    )
                }

                // Endpoint URL (Ollama, Custom, or optional proxy for cloud platforms)
                OutlinedTextField(
                    value = endpointUrl,
                    onValueChange = { endpointUrl = it },
                    label = {
                        Text(
                            if (initialConfig.platform == AIPlatform.OLLAMA || initialConfig.platform == AIPlatform.CUSTOM) {
                                "Endpoint Base URL"
                            } else {
                                "Custom Proxy / Gateway (Optional)"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            when (initialConfig.platform) {
                                AIPlatform.OLLAMA -> "http://10.0.2.2:11434"
                                AIPlatform.OPENAI -> "https://api.openai.com/v1"
                                AIPlatform.ANTHROPIC -> "https://api.anthropic.com/v1"
                                else -> "https://api.example.com"
                            }
                        )
                    }
                )

                // Default Model Name
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("Default Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Temperature Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Temperature", style = MaterialTheme.typography.bodySmall)
                        Text(
                            String.format(Locale.US, "%.2f", temperature),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0.0f..2.0f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialConfig.copy(
                            apiKey = apiKey,
                            endpointUrl = endpointUrl,
                            defaultModel = defaultModel,
                            temperature = temperature,
                            isEnabled = isEnabled
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
