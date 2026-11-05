package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal val LocalHomeGlassLayer = compositionLocalOf<GraphicsLayer?> { null }

/** Retains home pixels, never a live render graph that can reference its own blur source. */
@Composable
internal fun SettingsGlassBackdropHost(settings: Boolean, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var homeSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val pressure = remember { dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatRuntimeHealth.memory.map { it.level != "normal" }.distinctUntilChanged() }
    val underPressure by pressure.collectAsState(initial = false)
    val reduceEffects by dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure.guard.reduced.collectAsState()
    val captureEnabled = active && !underPressure && !reduceEffects && LocalOverlayMaterial.current == "glass" && LocalOverlayBlur.current == "auto"
    LaunchedEffect(settings, active, captureEnabled) {
        if (!captureEnabled) {
            homeSnapshot = null
            // The solid fallback must release the backing pixels as well as the
            // blur effect; otherwise heavy operations still pay the capture cost.
            layer.record(density, layoutDirection, IntSize(1, 1)) { }
        } else if (settings) {
            // The outgoing HomeScreen can still draw during the navigation transition.
            // Replaying its live layer as a Haze source creates Home -> blur -> Home.
            // Rasterize once so the settings source has no live dependency on Home.
            homeSnapshot = if (layer.size.width > 1 && layer.size.height > 1) {
                try {
                    layer.toImageBitmap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "Rendering",
                        "SETTINGS_BACKDROP_CAPTURE_FAILED · exception=${failure.javaClass.simpleName}",
                        "W"
                    )
                    null
                }
            } else {
                null
            }
        } else {
            homeSnapshot = null
        }
    }
    val base = appBaseBackground()
    CompositionLocalProvider(LocalHomeGlassLayer provides layer.takeIf { captureEnabled }, LocalAppBaseBackground provides base) {
        Box(Modifier.fillMaxSize().background(base)) {
            val snapshot = homeSnapshot
            if (settings && captureEnabled && snapshot != null) {
                Image(
                    bitmap = snapshot,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clearAndSetSemantics { }.glassBackdropSource()
                )
            }
            if (settings) {
                // Before capture (or for a direct settings deep link), show the tinted
                // material rather than asking Haze to sample a missing home source.
                val blurAvailable = captureEnabled && LocalGlassBackdropActive.current && snapshot != null
                CompositionLocalProvider(LocalGlassBackdropActive provides blurAvailable) {
                    AppGlassSurface(Modifier.fillMaxSize(), shape = androidx.compose.ui.graphics.RectangleShape) { }
                }
            }
            // Home capture records only its content, before this root background layer.
            // Settings samples that undarkened capture, then applies this same gradient once.
            AppBottomGradient()
            // Keep navigation at one composition call site when the backdrop changes.
            val scheme = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = scheme.copy(background = base.copy(alpha = 0f), surface = if (settings) Color.Transparent else scheme.surface)) {
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
