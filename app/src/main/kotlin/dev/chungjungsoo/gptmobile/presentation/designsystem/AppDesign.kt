package dev.chungjungsoo.gptmobile.presentation.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared values for touched screens; existing user palettes are preserved. */
internal object AppSpacing {
    val micro = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val screen = 20.dp
    val section = 28.dp
    val touchTarget = 48.dp
}

internal object AppShapes {
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(24.dp)
    val input = RoundedCornerShape(16.dp)
}

@Composable
internal fun AppEmptyState(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = AppShapes.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(AppSpacing.screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
