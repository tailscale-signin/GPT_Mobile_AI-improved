package dev.chungjungsoo.gptmobile.presentation.common

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource

@Composable
fun AppActionIcon(image: ImageVector, description: String?, modifier: Modifier = Modifier, destructive: Boolean = false) {
    Icon(image, description, modifier, tint = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else appOverlayTokens().foreground)
}

@Composable
fun BrandMark(@DrawableRes resource: Int, description: String?, modifier: Modifier = Modifier, monochrome: Boolean = false) {
    Icon(
        painterResource(resource),
        description,
        modifier,
        tint = if (monochrome) androidx.compose.material3.MaterialTheme.colorScheme.onSurface else Color.Unspecified
    )
}
