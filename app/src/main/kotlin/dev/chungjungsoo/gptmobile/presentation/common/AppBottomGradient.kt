package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal const val APP_BOTTOM_GRADIENT_COVERAGE = 0.30f
internal const val APP_BOTTOM_GRADIENT_DARKNESS = 0.28f
internal val LocalAppBaseBackground = compositionLocalOf { Color.Unspecified }

@Composable
internal fun appBaseBackground(): Color = LocalAppBaseBackground.current.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.background

/** Root viewport only. This layer never intercepts input or darkens foreground text. */
@Composable
internal fun AppBottomGradient() {
    val scrim = MaterialTheme.colorScheme.scrim
    Canvas(Modifier.fillMaxSize()) {
        val start = size.height * (1f - APP_BOTTOM_GRADIENT_COVERAGE)
        val stops = (0..16).map { index ->
            val t = index / 16f
            t to scrim.copy(alpha = APP_BOTTOM_GRADIENT_DARKNESS * t * t * (3 - 2 * t))
        }.toTypedArray()
        drawRect(
            Brush.verticalGradient(*stops, startY = start, endY = size.height),
            topLeft = Offset(0f, start),
            size = Size(size.width, size.height - start)
        )
    }
}

/** A new full-screen window has its own opaque scene; the underlying scene cannot be darkened twice. */
@Composable
internal fun AppSceneBackground(content: @Composable () -> Unit) {
    val base = appBaseBackground()
    CompositionLocalProvider(LocalAppBaseBackground provides base) {
        Box(Modifier.fillMaxSize().background(base)) {
            AppBottomGradient()
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = base.copy(alpha = 0f))) { content() }
        }
    }
}
