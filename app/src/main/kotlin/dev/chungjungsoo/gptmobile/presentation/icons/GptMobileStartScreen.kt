package dev.chungjungsoo.gptmobile.presentation.icons

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

val GptMobileStartScreen: ImageVector
    @Composable
    get() {
        val cyanColor = Color(0xFF17C5D7)
        val darkCapColor = Color(0xFF343B45)
        val bubbleColor = Color(0xFFFFFFFF)

        return Builder(
            name = "GptMobileStartScreen",
            defaultWidth = 240.dp,
            defaultHeight = 240.dp,
            viewportWidth = 240f,
            viewportHeight = 240f
        ).apply {
            // Circular base badge matching cyan stylize
            path(
                fill = SolidColor(cyanColor),
                pathFillType = NonZero
            ) {
                moveTo(120f, 16f)
                arcToRelative(104f, 104f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 208f)
                arcToRelative(104f, 104f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -208f)
                close()
            }

            // Outer white chat bubble badge (scaled and centered at 120, 116)
            path(
                fill = SolidColor(bubbleColor),
                pathFillType = NonZero
            ) {
                moveTo(120f - 48f, 116f - 44f)
                lineTo(120f + 56f, 116f - 48f)
                curveToRelative(2.1f, 0f, 4.2f, 2.1f, 4.2f, 4.2f)
                lineTo(120f + 42f, 116f + 48f)
                curveToRelative(0f, 2.1f, -2.1f, 4.2f, -4.2f, 4.2f)
                lineTo(120f + 42f, 116f + 68f)
                lineTo(120f + 16f, 116f + 50f)
                lineTo(120f - 56f, 116f + 46f)
                curveToRelative(-2.1f, 0f, -4.2f, -2.1f, -4.2f, -4.2f)
                lineTo(120f - 50f, 116f - 42f)
                curveToRelative(0f, -2.1f, 2.1f, -2.1f, 2.1f, -2.1f)
                close()
            }

            // Left ear / antenna
            path(
                fill = SolidColor(darkCapColor),
                pathFillType = NonZero
            ) {
                moveTo(120f - 34f, 116f - 14f)
                curveToRelative(-3.5f, 0f, -5f, 3.5f, -5f, 9f)
                lineToRelative(0f, 10f)
                curveToRelative(0f, 5.5f, 1.5f, 9f, 5f, 9f)
                close()
            }

            // Right ear / antenna
            path(
                fill = SolidColor(darkCapColor),
                pathFillType = NonZero
            ) {
                moveTo(120f + 34f, 116f - 14f)
                curveToRelative(3.5f, 0f, 5f, 3.5f, 5f, 9f)
                lineToRelative(0f, 10f)
                curveToRelative(0f, 5.5f, -1.5f, 9f, -5f, 9f)
                close()
            }

            // Central cyan robot face screen
            path(
                fill = SolidColor(cyanColor),
                pathFillType = NonZero
            ) {
                moveTo(120f - 24f, 116f - 24f)
                lineToRelative(48f, 0f)
                lineToRelative(0f, 48f)
                lineToRelative(-48f, 0f)
                close()
            }

            // Left eye
            path(
                fill = SolidColor(darkCapColor),
                pathFillType = NonZero
            ) {
                moveTo(120f - 10f, 116f - 5f)
                arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 11f)
                arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -11f)
                close()
            }

            // Right eye
            path(
                fill = SolidColor(darkCapColor),
                pathFillType = NonZero
            ) {
                moveTo(120f + 10f, 116f - 5f)
                arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 11f)
                arcToRelative(5.5f, 5.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -11f)
                close()
            }
        }.build()
    }

@Preview
@Composable
fun GPTLogoStartScreen() {
    GPTMobileTheme(
        dynamicTheme = DynamicTheme.ON,
        themeMode = ThemeMode.DARK
    ) {
        Image(imageVector = GptMobileStartScreen, contentDescription = "")
    }
}
