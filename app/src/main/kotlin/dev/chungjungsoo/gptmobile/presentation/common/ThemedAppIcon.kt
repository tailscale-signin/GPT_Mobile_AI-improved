package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.chungjungsoo.gptmobile.R

/** Shared GPT Mobile badge. Its accent follows the app's currently selected Material palette. */
@Composable
fun ThemedAppIcon(modifier: Modifier = Modifier, emblemScale: Float = 1f) {
    val colors = MaterialTheme.colorScheme
    val darkSurface = colors.surface.luminance() < 0.5f
    val bubble = if (darkSurface) colors.inverseSurface else colors.surface
    val details = if (darkSurface) colors.inverseOnSurface else colors.onSurface
    val gradientEnd = lerp(colors.primary, androidx.compose.ui.graphics.Color.Black, 0.28f)

    Box(
        modifier
            .clip(RoundedCornerShape(percent = 23))
            .background(Brush.verticalGradient(listOf(colors.primary, gradientEnd)))
            .semantics { contentDescription = "GPT Mobile" }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = emblemScale
                scaleY = emblemScale
            }
        ) {
            Image(
                painterResource(R.drawable.ic_app_emblem_bubble),
                null,
                Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(bubble)
            )
            Image(
                painterResource(R.drawable.ic_app_emblem_face),
                null,
                Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(colors.primary)
            )
            Image(
                painterResource(R.drawable.ic_app_emblem_details),
                null,
                Modifier.fillMaxSize(),
                colorFilter = ColorFilter.tint(details)
            )
        }
    }
}
