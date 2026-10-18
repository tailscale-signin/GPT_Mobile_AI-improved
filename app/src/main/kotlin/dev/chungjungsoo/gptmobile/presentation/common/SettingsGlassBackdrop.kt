package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalHomeGlassLayer = compositionLocalOf<GraphicsLayer?> { null }

/** Retains rendered home pixels without a second HomeScreen, its effects, or clickable controls. */
@Composable
internal fun SettingsGlassBackdropHost(settings: Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active) {
        // Drop private conversation pixels when the app leaves the foreground.
        if (!active) layer.record(density, layoutDirection, IntSize(1, 1)) { }
    }
    CompositionLocalProvider(LocalHomeGlassLayer provides layer) {
        Box(Modifier.fillMaxSize()) {
            if (settings && active) {
                Box(
                    Modifier.fillMaxSize().clearAndSetSemantics { }
                        .glassBackdropSource()
                        .drawWithContent { drawLayer(layer) }
                )
            }
            if (settings) {
                AppGlassSurface(Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) { }
            }
            // Keep navigation at one composition call site when the backdrop changes.
            val scheme = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = if (settings) scheme.copy(background = Color.Transparent, surface = Color.Transparent) else scheme) {
                content()
            }
        }
    }
}

@Composable
internal fun Modifier.captureHomeGlassBackdrop(): Modifier {
    val layer = LocalHomeGlassLayer.current ?: return this
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}
