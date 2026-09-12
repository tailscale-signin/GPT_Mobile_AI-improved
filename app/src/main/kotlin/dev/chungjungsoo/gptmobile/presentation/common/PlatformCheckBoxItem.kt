package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlatformCheckBoxItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean = true,
    isFavorite: Boolean = false,
    labels: String? = null,
    title: String = stringResource(R.string.sample_item_title),
    description: String? = stringResource(R.string.sample_item_description),
    onLongClickEvent: (() -> Unit)? = null,
    onClickEvent: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val labelsList = remember(labels) {
        val raw = labels?.trim()
        if (raw.isNullOrBlank()) {
            emptyList()
        } else if (raw.startsWith("[") && raw.endsWith("]")) {
            raw.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    val rowModifier = if (enabled) {
        if (onLongClickEvent != null) {
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClickEvent,
                    onLongClick = onLongClickEvent
                )
                .padding(top = 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
        } else {
            modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current
                ) { onClickEvent.invoke() }
                .padding(top = 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
        }
    } else {
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
    }
    val textModifier = Modifier.alpha(if (enabled) 1.0f else 0.38f)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            enabled = enabled,
            checked = selected,
            interactionSource = interactionSource,
            onCheckedChange = { onClickEvent.invoke() }
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    modifier = textModifier,
                    style = MaterialTheme.typography.titleMedium
                )
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            description?.let {
                Text(
                    text = it,
                    modifier = textModifier,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (labelsList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    labelsList.forEach { label ->
                        val (chipBg, chipBorder, chipText) = getBeveledLabelColors(label)
                        Surface(
                            shape = CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp, topEnd = 0.dp, bottomStart = 0.dp),
                            color = chipBg,
                            border = BorderStroke(1.dp, chipBorder)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = chipText,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Derives consistent color-coded palette for platform beveled label badges.
 */
private fun getBeveledLabelColors(label: String): Triple<Color, Color, Color> {
    val hash = abs(label.hashCode())
    val palette = listOf(
        Triple(Color(0x2A1976D2), Color(0xFF1976D2), Color(0xFF64B5F6)), // Blue
        Triple(Color(0x2A388E3C), Color(0xFF388E3C), Color(0xFF81C784)), // Green
        Triple(Color(0x2A7B1FA2), Color(0xFF7B1FA2), Color(0xFFBA68C8)), // Purple
        Triple(Color(0x2AE65100), Color(0xFFE65100), Color(0xFFFFB74D)), // Orange
        Triple(Color(0x2A00838F), Color(0xFF00838F), Color(0xFF4DD0E1)), // Cyan
        Triple(Color(0x2AC2185B), Color(0xFFC2185B), Color(0xFFF06292)), // Pink
        Triple(Color(0x2A5D4037), Color(0xFF5D4037), Color(0xFFA1887F)) // Brown
    )
    return palette[hash % palette.size]
}
