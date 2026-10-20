package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slide and fade for subpages that share one navigation destination; existing callers keep the API. */
class BackFade internal constructor(private val scope: CoroutineScope) {
    private val frame = Animatable(Offset(0f, 1f), Offset.VectorConverter)
    private var running by mutableStateOf(false)
    val modifier: Modifier get() = Modifier.graphicsLayer {
        alpha = frame.value.y.coerceIn(0f, 1f)
    }

    fun fade(action: () -> Unit) {
        if (running) return
        running = true
        scope.launch {
            try {
                frame.animateTo(Offset(0f, 0f), tween(140, easing = FastOutSlowInEasing))
                action()
                frame.snapTo(Offset(0f, 0f))
                frame.animateTo(Offset(0f, 1f), tween(160, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) { frame.snapTo(Offset(0f, 1f)) }
                running = false
            }
        }
    }
}

@Composable
fun rememberBackFade(): BackFade {
    val scope = rememberCoroutineScope()
    return remember(scope) { BackFade(scope) }
}
