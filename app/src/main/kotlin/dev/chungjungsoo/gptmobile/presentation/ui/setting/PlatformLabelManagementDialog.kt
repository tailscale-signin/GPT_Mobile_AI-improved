package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.label.PREDEFINED_LABEL_COLORS
import dev.chungjungsoo.gptmobile.data.label.PlatformLabel
import dev.chungjungsoo.gptmobile.data.label.PlatformLabelManager

/**
 * Dialog for selecting and creating shared labels across AI platforms.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformLabelManagementDialog(
    currentLabels: List<String>,
    allAvailableLabels: List<PlatformLabel> = emptyList(),
    onDismissRequest: () -> Unit,
    onSaveLabels: (List<String>) -> Unit,
    onCreateNewLabel: ((PlatformLabel) -> Unit)? = null
) {
    var selectedLabels by remember { mutableStateOf(currentLabels.toSet()) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var newLabelName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PREDEFINED_LABEL_COLORS.first()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(R.string.platform_labels))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                Text(
                    text = stringResource(R.string.platform_labels_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Currently assigned or available labels
                val combinedLabelNames = (allAvailableLabels.map { it.name } + selectedLabels).distinct()
                if (combinedLabelNames.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        combinedLabelNames.forEach { labelName ->
                            val isSelected = selectedLabels.contains(labelName)
                            val labelColorHex = allAvailableLabels.find { it.name.equals(labelName, ignoreCase = true) }?.colorHex
                                ?: "#4CAF50"
                            val parsedColor = runCatching { Color(android.graphics.Color.parseColor(labelColorHex)) }
                                .getOrDefault(MaterialTheme.colorScheme.primary)

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedLabels = if (isSelected) {
                                        selectedLabels - labelName
                                    } else {
                                        selectedLabels + labelName
                                    }
                                },
                                label = { Text(labelName) },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(parsedColor)
                                    )
                                },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_labels),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                // Create new label expandable section
                AnimatedVisibility(visible = isCreatingNew) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = newLabelName,
                                onValueChange = {
                                    newLabelName = it
                                    validationError = null
                                },
                                label = { Text(stringResource(R.string.label_name)) },
                                singleLine = true,
                                isError = validationError != null,
                                supportingText = validationError?.let { { Text(it) } },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Color palette picker
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PREDEFINED_LABEL_COLORS.forEach { colorHex ->
                                    val color = Color(android.graphics.Color.parseColor(colorHex))
                                    val isColorSelected = selectedColor == colorHex

                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .clickable { selectedColor = colorHex }
                                            .then(
                                                if (isColorSelected) {
                                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                } else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isColorSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { isCreatingNew = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val error = PlatformLabelManager.validate(newLabelName, selectedColor, null)
                                        if (error != null) {
                                            validationError = error
                                        } else {
                                            val created = PlatformLabel(name = newLabelName.trim(), colorHex = selectedColor)
                                            onCreateNewLabel?.invoke(created)
                                            selectedLabels = selectedLabels + created.name
                                            newLabelName = ""
                                            isCreatingNew = false
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.confirm))
                                }
                            }
                        }
                    }
                }

                if (!isCreatingNew) {
                    TextButton(
                        onClick = { isCreatingNew = true },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.add_label))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSaveLabels(selectedLabels.toList()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
