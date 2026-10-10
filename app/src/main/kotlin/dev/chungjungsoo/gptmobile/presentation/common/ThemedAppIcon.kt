package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R

/** Reference-traced emblem on a theme-coloured circle, fitted without stretching. */
@Composable
fun ThemedAppIcon(modifier: Modifier = Modifier, emblemScale: Float = 1f) {
    val colors = MaterialTheme.colorScheme
    val gradientEnd = lerp(colors.primary, Color.Black, 0.28f)
    val emblemPainter = painterResource(R.drawable.ic_app_emblem)
    val description = stringResource(R.string.app_name)

    Canvas(
        modifier
            .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
    ) {
        val side = size.minDimension
        val badgeSize = Size(side, side)
        val badgeCenter = Offset(side / 2f, side / 2f)
        // Fit the background and reference artwork to the same square.
        // A caller's wide/tall bounds must never turn the badge into an ellipse.
        translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f) {
            drawCircle(
                brush = Brush.verticalGradient(listOf(colors.primary, gradientEnd), endY = side),
                radius = side / 2f,
                center = badgeCenter
            )
            scale(scale = emblemScale.coerceIn(0f, 1f), pivot = badgeCenter) {
                // Tint only the background. Keep the reference's white panel,
                // pale shaded lip, cyan face and navy contours in every theme.
                with(emblemPainter) { draw(badgeSize) }
            }
        }
    }
}
