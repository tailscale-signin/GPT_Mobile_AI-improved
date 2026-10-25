package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

internal val LocalGlassBackdrop = compositionLocalOf<HazeState?> { null }
private val LocalInlineGlassBackdrop = compositionLocalOf<HazeState?> { null }
internal val LocalGlassBackdropActive = compositionLocalOf { false }
private val LocalGlassPopupSequence = compositionLocalOf<java.util.concurrent.atomic.AtomicInteger?> { null }
internal val LocalOverlayWindow = compositionLocalOf { false }
internal const val GLASS_OPACITY = 0.70f
internal val GLASS_BLUR_RADIUS = 40.dp

/** Captures rendered app pixels in memory. Popup windows sample this layer, never their text. */
@Composable
fun GlassBackdropHost(content: @Composable () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val state = rememberHazeState()
    val popupSequence = remember(state) { java.util.concurrent.atomic.AtomicInteger() }
    CompositionLocalProvider(LocalGlassBackdrop provides state, LocalGlassBackdropActive provides active, LocalGlassPopupSequence provides popupSequence) {
        // Removing the source when stopped releases its graphics layer, including private pixels.
        Box(Modifier.fillMaxSize().then(if (active) Modifier.hazeSource(state) else Modifier)) { content() }
    }
}

/** Isolates in-page controls from other destinations while popups retain the window backdrop. */
@Composable
internal fun GlassBackdropBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalInlineGlassBackdrop provides state) {
        Box(modifier, content = content)
    }
}

/** A sibling source for same-window controls, below the enclosing navigation source. */
@Composable
fun Modifier.glassBackdropSource(): Modifier {
    val state = LocalInlineGlassBackdrop.current ?: LocalGlassBackdrop.current
    return if (state != null && LocalGlassBackdropActive.current) hazeSource(state, zIndex = -1f) else this
}

@Composable
internal fun hasGlassBackdrop(force: Boolean = false): Boolean = LocalGlassBackdrop.current != null &&
    LocalGlassBackdropActive.current &&
    (force || LocalOverlayMaterial.current == "glass" && LocalOverlayBlur.current == "auto")

@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
@Composable
internal fun Modifier.glassBackdrop(shape: Shape, opacity: Float = GLASS_OPACITY, captureAsPopup: Boolean = false, blurRadius: androidx.compose.ui.unit.Dp = GLASS_BLUR_RADIUS, force: Boolean = false): Modifier {
    val state = (if (captureAsPopup) LocalGlassBackdrop.current else LocalInlineGlassBackdrop.current ?: LocalGlassBackdrop.current) ?: return this
    // Allocate while visible, even with blur Off, so toggling does not reorder
    // already-open parent and child windows. A source excludes itself and newer windows.
    val sequence = LocalGlassPopupSequence.current
    val popupZ = if (captureAsPopup) remember(state) { (sequence?.incrementAndGet() ?: 1).toFloat() } else 0f
    if (!hasGlassBackdrop(force)) return this
    DisposableEffect(state, captureAsPopup) {
        val release = dev.chungjungsoo.gptmobile.data.diagnostics.RenderingEffectDiagnostics.enter(dev.chungjungsoo.gptmobile.data.diagnostics.RenderingEffectDiagnostics.Effect.GLASS_BLUR)
        onDispose { release() }
    }
    val tokens = appOverlayTokens(opacity)
    val tint = tokens.background.copy(alpha = opacity.coerceIn(0f, 1f))
    val source = if (captureAsPopup) hazeSource(state, zIndex = popupZ) else this
    return source.clip(shape).hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.background,
            tints = listOf(HazeTint(tint)),
            blurRadius = blurRadius,
            noiseFactor = 0.035f,
            fallbackTint = HazeTint(tint)
        )
    ) { if (captureAsPopup) canDrawArea = { area -> area.zIndex < popupZ } }
}

/** A patterned sibling source makes the preview demonstrate actual backdrop blur. */
@Composable
fun GlassMaterialPreview() {
    val state = rememberHazeState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    CompositionLocalProvider(LocalGlassBackdrop provides state, LocalGlassBackdropActive provides active) {
        Box(Modifier.fillMaxWidth().height(112.dp)) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize().then(if (active) Modifier.hazeSource(state) else Modifier)) {
                colors.forEachIndexed { index, color ->
                    drawCircle(color, radius = size.height * 0.6f, center = androidx.compose.ui.geometry.Offset(size.width * (index + 0.5f) / 3, size.height * 0.5f))
                }
            }
            AppGlassSurface(Modifier.fillMaxSize()) {
                androidx.compose.material3.Text("Menu and dialog preview", Modifier.padding(24.dp))
            }
        }
    }
}

/** Full-screen dialog content shares the same backdrop renderer without affecting normal screens. */
@Composable
fun Modifier.glassWindowBackdrop(shape: Shape = androidx.compose.ui.graphics.RectangleShape): Modifier = if (LocalOverlayWindow.current) glassBackdrop(shape, captureAsPopup = true) else this

@Composable
fun overlayWindowContainerColor(): androidx.compose.ui.graphics.Color = when {
    !LocalOverlayWindow.current -> MaterialTheme.colorScheme.background
    hasGlassBackdrop() -> androidx.compose.ui.graphics.Color.Transparent
    else -> appOverlayTokens().background
}
