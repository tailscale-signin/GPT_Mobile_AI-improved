package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Cache maintenance has no chat layout or accessibility announcement. */
@Composable
internal fun ChatMemoryStatus() {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = "normal"
            while (true) {
                val current = withContext(Dispatchers.Default) {
                    val runtime = Runtime.getRuntime()
                    val info = ActivityManager.MemoryInfo()
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
                    ChatMemorySample(runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), android.os.Debug.getPss(), info.lowMemory)
                }
                ChatRuntimeHealth.memory(current)
                if (current.level != previous) {
                    if (current.level != "normal") clearChatRenderingCaches()
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                        "ChatMemory",
                        "level=${current.level}; heapBytes=${current.usedHeapBytes}; pssKb=${current.processPssKb}"
                    )
                    previous = current.level
                }
                delay(5_000)
            }
        }
    }
}
