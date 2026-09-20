package com.example.gptmobileai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.data.label.PREDEFINED_LABEL_COLORS
import dev.chungjungsoo.gptmobile.data.label.PlatformLabel
import dev.chungjungsoo.gptmobile.data.label.PlatformLabelManager

/**
 * Interactive model & platform label selector supporting shared labels across platforms,
 * color selection from the palette, and quick creation popup.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformLabelManagerSection(
    assignedLabels: List<String>,
    allAvailableLabels: List<PlatformLabel>,
    onLabelToggled: (String) -> Unit,
    onNewLabelCreated: (PlatformLabel) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Platform & Model Labels",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Label", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (allAvailableLabels.isEmpty() && assignedLabels.isEmpty()) {
            Text(
                text = "No shared labels yet. Create one to organize models across platforms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                allAvailableLabels.forEach { labelItem ->
                    val isAssigned = assignedLabels.contains(labelItem.name)
                    val labelColor = try {
                        Color(android.graphics.Color.parseColor(labelItem.colorHex))
                    } catch (e: Exception) {
                        MaterialTheme.colorScheme.primary
                    }

                    FilterChip(
                        selected = isAssigned,
                        onClick = { onLabelToggled(labelItem.name) },
                        label = {
                            Text(
                                text = labelItem.name,
                                fontSize = 12.sp,
                                fontWeight = if (isAssigned) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(labelColor)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = labelColor.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }

        if (showCreateDialog) {
            CreatePlatformLabelDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { newLabel ->
                    onNewLabelCreated(newLabel)
                    onLabelToggled(newLabel.name)
                    showCreateDialog = false
                }
            )
        }
    }
}

/**
 * Dialog popup to create a new shared platform label with custom color picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreatePlatformLabelDialog(
    onDismiss: () -> Unit,
    onConfirm: (PlatformLabel) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf(PREDEFINED_LABEL_COLORS.first()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Label,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Shared Label")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text("Label Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = validationError != null
                )

                validationError?.let { error ->
                    Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Select Label Color",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PREDEFINED_LABEL_COLORS.forEach { hex ->
                        val color = try {
                            Color(android.graphics.Color.parseColor(hex))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                        val isSelected = selectedColorHex.equals(hex, ignoreCase = true)

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val error = PlatformLabelManager.validate(name, selectedColorHex, description.ifBlank { null })
                    if (error != null) {
                        validationError = error
                    } else {
                        onConfirm(
                            PlatformLabel(
                                name = name.trim(),
                                colorHex = selectedColorHex,
                                description = description.trim().ifBlank { null }
                            )
                        )
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
