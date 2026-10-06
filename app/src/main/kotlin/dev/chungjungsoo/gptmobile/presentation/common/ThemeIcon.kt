package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

/** Shared icon treatment. Accents follow the palette, while buttons keep their contrast color. */
@Composable
private fun iconTint(requested: Color): Color {
    if (requested != Color.Unspecified) return requested
    val colors = MaterialTheme.colorScheme
    val content = LocalContentColor.current
    return if (content == colors.onSurface || content == colors.onSurfaceVariant || content == Color.Unspecified) colors.primary else content
}

@Composable
fun ThemeIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    androidx.compose.material3.Icon(imageVector, contentDescription, modifier, iconTint(tint))
}

@Composable
fun ThemeIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    androidx.compose.material3.Icon(painter, contentDescription, modifier, iconTint(tint))
}

@Composable
fun ThemeIcon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    androidx.compose.material3.Icon(bitmap, contentDescription, modifier, iconTint(tint))
}
