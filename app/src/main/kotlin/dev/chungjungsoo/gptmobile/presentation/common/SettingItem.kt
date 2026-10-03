package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R

@Composable
fun SettingItem(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    onItemClick: () -> Unit,
    showTrailingIcon: Boolean,
    showLeadingIcon: Boolean,
    leadingIcon: @Composable () -> Unit? = {},
    trailingBadge: @Composable (() -> Unit)? = null
) {
    val clickableModifier = if (enabled) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = 8.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    }
    val colors = ListItemDefaults.colors()
    val displayTitle = title.split(Regex("\\s+")).joinToString(" ") { word ->
        word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
    }
    val compactDescription = description?.takeIf { it.length <= 72 }

    val trailingContentComposable: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (description != null && compactDescription == null) {
                SettingsHelpIcon(description)
            }
            trailingBadge?.invoke()
            if (showTrailingIcon) {
                Icon(
                    ImageVector.vectorResource(id = R.drawable.ic_round_arrow_right),
                    contentDescription = stringResource(R.string.arrow_icon)
                )
            }
        }
    }

    if (showLeadingIcon) {
        ListItem(
            modifier = clickableModifier,
            headlineContent = { Text(displayTitle, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                compactDescription?.let { Text(it, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = { leadingIcon() },
            trailingContent = trailingContentComposable,
            colors = ListItemDefaults.colors(
                headlineColor = if (enabled) colors.headlineColor else colors.disabledHeadlineColor,
                supportingColor = if (enabled) colors.supportingTextColor else colors.disabledHeadlineColor,
                trailingIconColor = if (enabled) colors.trailingIconColor else colors.disabledTrailingIconColor
            )
        )
    } else {
        ListItem(
            modifier = clickableModifier,
            headlineContent = { Text(displayTitle) },
            supportingContent = {
                compactDescription?.let { Text(it) }
            },
            trailingContent = trailingContentComposable,
            colors = ListItemDefaults.colors(
                headlineColor = if (enabled) colors.headlineColor else colors.disabledHeadlineColor,
                supportingColor = if (enabled) colors.supportingTextColor else colors.disabledHeadlineColor,
                trailingIconColor = if (enabled) colors.trailingIconColor else colors.disabledTrailingIconColor
            )
        )
    }
}

@Composable
fun SettingsHelpIcon(
    description: String,
    modifier: Modifier = Modifier,
    title: String = "About This Setting"
) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("?", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(displayTitle) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            }
        )
    }
}
