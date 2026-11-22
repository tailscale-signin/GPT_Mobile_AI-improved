package dev.chungjungsoo.gptmobile.presentation.common

import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity

/** Retain secure-window protection until the last visible private conversation leaves the window. */
@Composable
internal fun PrivateConversationScreenProtection(locked: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ProtectedActivity>().firstOrNull()
    }
    DisposableEffect(activity, locked) {
        val release = if (locked) activity?.protectPrivateConversation() else null
        onDispose { release?.invoke() }
    }
}
