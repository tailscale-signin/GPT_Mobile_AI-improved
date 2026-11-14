package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/** Window bounds are read before any chat/parent IME padding, excluding system bars. */
@Composable
internal fun rememberChatWindowHeight(): Dp {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val container = LocalWindowInfo.current.containerSize
    val height = remember(context, configuration, container) {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        (metrics.bounds.height() - bars.top - bars.bottom).coerceAtLeast(0)
    }
    return with(LocalDensity.current) { height.toDp() }
}
