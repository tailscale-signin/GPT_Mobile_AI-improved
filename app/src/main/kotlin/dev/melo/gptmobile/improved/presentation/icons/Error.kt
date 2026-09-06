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

val Error: ImageVector
    get() = Builder(
        name = "Error Icon",
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
            moveTo(480.0f, 680.0f)
            quadToRelative(17.0f, 0.0f, 28.5f, -11.5f)
            reflectiveQuadTo(520.0f, 640.0f)
            reflectiveQuadToRelative(-11.5f, -28.5f)
            reflectiveQuadTo(480.0f, 600.0f)
            reflectiveQuadToRelative(-28.5f, 11.5f)
            reflectiveQuadTo(440.0f, 640.0f)
            reflectiveQuadToRelative(11.5f, 28.5f)
            reflectiveQuadTo(480.0f, 680.0f)
            close()
            moveTo(440.0f, 520.0f)
            horizontalLineToRelative(80.0f)
            verticalLineToRelative(-240.0f)
            horizontalLineToRelative(-80.0f)
            verticalLineToRelative(240.0f)
            close()
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
        }
    }
        .build()
