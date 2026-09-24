package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.PROFILE_LABEL_COLOR_PRESETS
import dev.chungjungsoo.gptmobile.data.model.ProfileLabel
import dev.chungjungsoo.gptmobile.data.model.normalizeLabelColor
import kotlin.math.abs

@Composable
fun BeveledProfileLabel(
    label: ProfileLabel,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val (background, border, textColor) = profileLabelColors(label, selected)
    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Surface(
        modifier = clickableModifier,
        shape = CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp, topEnd = 0.dp, bottomStart = 0.dp),
        color = background,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border)
    ) {
        Text(
            text = label.name,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ProfileLabelEditorDialog(
    currentLabels: List<ProfileLabel>,
    reusableLabels: List<ProfileLabel>,
    onDismiss: () -> Unit,
    onSave: (List<ProfileLabel>) -> Unit
) {
    var working by remember(currentLabels) { mutableStateOf(currentLabels) }
    var draftName by remember { mutableStateOf("") }
    var draftColor by remember { mutableStateOf(PROFILE_LABEL_COLOR_PRESETS.first()) }
    var editingKey by remember { mutableStateOf<String?>(null) }

    fun beginEdit(label: ProfileLabel) {
        draftName = label.name
        draftColor = normalizeLabelColor(label.colorHex) ?: PROFILE_LABEL_COLOR_PRESETS.first()
        editingKey = label.key
    }

    fun upsertDraft() {
        val name = draftName.trim()
        if (name.isBlank()) return
        val label = ProfileLabel(name, draftColor)
        val key = editingKey ?: label.key
        val withoutOld = working.filterNot { it.key == key || it.key == label.key }
        working = withoutOld + label
        draftName = ""
        draftColor = PROFILE_LABEL_COLOR_PRESETS.first()
        editingKey = null
    }

    val available = reusableLabels.filter { reusable -> working.none { it.key == reusable.key } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile labels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use colored labels to group AI profiles. Reusing the same label links it across profiles and makes it available as a model filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (working.isNotEmpty()) {
                    Text("Assigned", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        working.forEach { label ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BeveledProfileLabel(
                                    label = label,
                                    selected = editingKey == label.key,
                                    onClick = { beginEdit(label) }
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    working = working.filterNot { it.key == label.key }
                                    if (editingKey == label.key) {
                                        editingKey = null
                                        draftName = ""
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove ${label.name}")
                                }
                            }
                        }
                    }
                }

                if (available.isNotEmpty()) {
                    Text("Reuse an existing label", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        available.forEach { label ->
                            BeveledProfileLabel(
                                label = label,
                                onClick = { working = working + label }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.take(32) },
                    label = { Text(if (editingKey == null) "New label" else "Label name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROFILE_LABEL_COLOR_PRESETS.forEach { colorHex ->
                        val color = colorFromHex(colorHex) ?: MaterialTheme.colorScheme.primary
                        Box(
                            modifier = Modifier
                                .size(if (draftColor == colorHex) 34.dp else 30.dp)
                                .clickable { draftColor = colorHex },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                modifier = Modifier.size(if (draftColor == colorHex) 30.dp else 24.dp),
                                shape = CircleShape,
                                color = color,
                                border = if (draftColor == colorHex) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                } else null
                            ) {}
                        }
                    }
                }
                Button(
                    onClick = ::upsertDraft,
                    enabled = draftName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (editingKey == null) "Add label" else "Update label")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(working) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun profileLabelColors(label: ProfileLabel, selected: Boolean = false): Triple<Color, Color, Color> {
    val explicit = colorFromHex(label.colorHex)
    val accent = explicit ?: fallbackLabelColor(label.name)
    val background = accent.copy(alpha = if (selected) 0.28f else 0.16f)
    return Triple(background, accent, MaterialTheme.colorScheme.onSurface)
}

fun colorFromHex(value: String?): Color? {
    val normalized = normalizeLabelColor(value) ?: return null
    return runCatching {
        val argb = if (normalized.length == 6) "FF$normalized" else normalized
        Color(argb.toLong(16))
    }.getOrNull()
}

private fun fallbackLabelColor(label: String): Color {
    val palette = listOf(
        Color(0xFF1976D2),
        Color(0xFF388E3C),
        Color(0xFF7B1FA2),
        Color(0xFFE65100),
        Color(0xFF00838F),
        Color(0xFFC2185B),
        Color(0xFF5D4037)
    )
    return palette[abs(label.hashCode()) % palette.size]
}
