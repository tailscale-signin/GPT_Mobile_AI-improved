package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Back transitions for subpages that share one navigation destination. */
class BackFade internal constructor(private val scope: CoroutineScope) {
    private val opacity = Animatable(1f)
    private var running by mutableStateOf(false)
    val modifier: Modifier get() = Modifier.graphicsLayer { alpha = opacity.value }

    fun fade(action: () -> Unit) {
        if (running) return
        running = true
        scope.launch {
            try {
                opacity.animateTo(0f, tween(500))
                action()
                opacity.snapTo(1f)
            } finally {
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
