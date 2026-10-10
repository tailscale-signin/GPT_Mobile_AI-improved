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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R

/** Circular brand badge, uniformly fitted and centered even in a non-square parent. */
@Composable
fun ThemedAppIcon(modifier: Modifier = Modifier, emblemScale: Float = 1f) {
    val colors = MaterialTheme.colorScheme
    val darkSurface = colors.surface.luminance() < 0.5f
    val bubble = if (darkSurface) colors.inverseSurface else colors.surface
    val details = if (darkSurface) colors.inverseOnSurface else colors.onSurface
    val gradientEnd = lerp(colors.primary, Color.Black, 0.28f)
    val bubblePainter = painterResource(R.drawable.ic_app_emblem_bubble)
    val facePainter = painterResource(R.drawable.ic_app_emblem_face)
    val detailsPainter = painterResource(R.drawable.ic_app_emblem_details)
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
        // Fit the background and all three vector layers to the same square.
        // A caller's wide/tall bounds must never turn the badge into an ellipse.
        translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f) {
            drawCircle(
                brush = Brush.verticalGradient(listOf(colors.primary, gradientEnd), endY = side),
                radius = side / 2f,
                center = badgeCenter
            )
            scale(scale = emblemScale.coerceIn(0f, 1f), pivot = badgeCenter) {
                with(bubblePainter) { draw(badgeSize, colorFilter = ColorFilter.tint(bubble)) }
                with(facePainter) { draw(badgeSize, colorFilter = ColorFilter.tint(colors.primary)) }
                with(detailsPainter) { draw(badgeSize, colorFilter = ColorFilter.tint(details)) }
            }
        }
    }
}
