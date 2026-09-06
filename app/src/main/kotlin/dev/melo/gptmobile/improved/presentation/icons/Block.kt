package dev.melo.gptmobile.improved.presentation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Block: ImageVector
    get() = Builder(
        name = "Block Icon",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 960.0f,
        viewportHeight = 960.0f
    ).apply {
        path(
            fill = SolidColor(Color(0xFFBA1A1A)),
            stroke = null,
            strokeLineWidth = 0.0f,
            strokeLineCap = Butt,
            strokeLineJoin = Miter,
            strokeLineMiter = 4.0f,
            pathFillType = NonZero
        ) {
            moveTo(480.0f, 880.0f)
            quadToRelative(-83.0f, 0.0f, -156.0f, -31.5f)
            reflectiveQuadTo(197.0f, 763.0f)
            reflectiveQuadTo(111.5f, 636.0f)
            reflectiveQuadTo(80.0f, 480.0f)
            reflectiveQuadToRelative(31.5f, -156.0f)
            reflectiveQuadTo(197.0f, 197.0f)
            reflectiveQuadToRelative(127.0f, -85.5f)
            reflectiveQuadTo(480.0f, 80.0f)
            reflectiveQuadToRelative(156.0f, 31.5f)
            reflectiveQuadTo(763.0f, 197.0f)
            reflectiveQuadToRelative(85.5f, 127.0f)
            reflectiveQuadTo(880.0f, 480.0f)
            reflectiveQuadToRelative(-31.5f, 156.0f)
            reflectiveQuadTo(763.0f, 763.0f)
            reflectiveQuadToRelative(-127.0f, 85.5f)
            reflectiveQuadTo(480.0f, 880.0f)
            close()
            moveTo(480.0f, 800.0f)
            quadToRelative(133.0f, 0.0f, 226.5f, -93.5f)
            reflectiveQuadTo(800.0f, 480.0f)
            quadToRelative(0.0f, -54.0f, -18.0f, -104.0f)
            reflectiveQuadToRelative(-54.0f, -92.0f)
            lineTo(284.0f, 728.0f)
            quadToRelative(42.0f, 36.0f, 92.0f, 54.0f)
            reflectiveQuadToRelative(104.0f, 18.0f)
            close()
            moveTo(232.0f, 676.0f)
            lineToRelative(444.0f, -444.0f)
            quadToRelative(-42.0f, -36.0f, -92.0f, -54.0f)
            reflectiveQuadToRelative(-104.0f, -18.0f)
            quadToRelative(-133.0f, 0.0f, -226.5f, 93.5f)
            reflectiveQuadTo(160.0f, 480.0f)
            quadToRelative(0.0f, 54.0f, 18.0f, 104.0f)
            reflectiveQuadToRelative(54.0f, 92.0f)
            close()
        }
    }
        .build()
