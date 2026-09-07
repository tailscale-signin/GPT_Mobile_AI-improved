package dev.chungjungsoo.gptmobile.presentation.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Round
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

val GptMobileStartScreen: ImageVector
    @Composable
    get() {
        val cyanColor = Color(0xFF17C5D7)
        val strokeDarkColor = Color(0xFF343B45)
        val bubbleFillColor = Color(0xFFF2F0F3)

        return Builder(
            name = "GptMobileStartScreen",
            defaultWidth = 240.dp,
            defaultHeight = 240.dp,
            viewportWidth = 240f,
            viewportHeight = 240f
        ).apply {
            // Circular base badge matching cyan styling
            path(
                fill = SolidColor(cyanColor)
            ) {
                moveTo(120f, 16f)
                arcToRelative(104f, 104f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 208f)
                arcToRelative(104f, 104f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -208f)
                close()
            }

            // Robot emblem with exact geometry centered and scaled
            group(
                scaleX = 0.44f,
                scaleY = 0.44f,
                translationX = 37.4f,
                translationY = 52.1f
            ) {
                // Outer chat bubble
                path(
                    fill = SolidColor(bubbleFillColor),
                    stroke = SolidColor(strokeDarkColor),
                    strokeLineWidth = 16f,
                    strokeLineCap = Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(349.46f, 8.08f)
                    lineTo(63.72f, 25.34f)
                    curveToRelative(-9.77f, 0.4f, -15.99f, 6.53f, -17.5f, 14.88f)
                    lineTo(8.21f, 216.35f)
                    curveToRelative(-1.56f, 9.53f, 5.76f, 14.74f, 17.5f, 14.88f)
                    lineTo(245.38f, 231.23f)
                    arcToRelative(4.17f, 4.17f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 3.12f, dy1 = 1.3f)
                    lineToRelative(44.51f, 60.84f)
                    curveToRelative(12.23f, 13.8f, 26.51f, 5.17f, 29.01f, -11.2f)
                    lineToRelative(9.04f, -50.93f)
                    lineToRelative(35.9f, -208.27f)
                    curveTo(368.39f, 15.22f, 362.73f, 7.1f, 349.46f, 8.08f)
                    close()
                }

                // Inner robot face screen in Cyan
                path(
                    fill = SolidColor(cyanColor),
                    stroke = SolidColor(strokeDarkColor),
                    strokeLineWidth = 16f,
                    strokeLineCap = Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(125.75f, 74.14f)
                    lineTo(241.75f, 74.14f)
                    arcTo(12f, 12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 253.75f, y1 = 86.14f)
                    lineTo(253.75f, 162.14f)
                    arcTo(12f, 12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 241.75f, y1 = 174.14f)
                    lineTo(125.75f, 174.14f)
                    arcTo(12f, 12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 113.75f, y1 = 162.14f)
                    lineTo(113.75f, 86.14f)
                    arcTo(12f, 12f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 125.75f, y1 = 74.14f)
                    close()
                }

                // Left antenna/ear
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(strokeDarkColor),
                    strokeLineWidth = 16f,
                    strokeLineCap = Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(83.75f, 106.37f)
                    lineTo(83.75f, 141.9f)
                }

                // Right antenna/ear
                path(
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(strokeDarkColor),
                    strokeLineWidth = 16f,
                    strokeLineCap = Round,
                    strokeLineJoin = StrokeJoin.Round
                ) {
                    moveTo(283.75f, 106.37f)
                    lineTo(283.75f, 141.9f)
                }

                // Left eye
                path(
                    fill = SolidColor(strokeDarkColor)
                ) {
                    moveTo(152.63f, 115.29f)
                    moveToRelative(-10f, 0f)
                    arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 20f, dy1 = 0f)
                    arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -20f, dy1 = 0f)
                }

                // Right eye
                path(
                    fill = SolidColor(strokeDarkColor)
                ) {
                    moveTo(215.11f, 115.29f)
                    moveToRelative(-10f, 0f)
                    arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 20f, dy1 = 0f)
                    arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -20f, dy1 = 0f)
                }
            }
        }.build()
    }

@Preview
@Composable
fun GptMobileStartScreenPreview() {
    GPTMobileTheme {
        androidx.compose.foundation.Image(
            imageVector = GptMobileStartScreen,
            contentDescription = null
        )
    }
}
