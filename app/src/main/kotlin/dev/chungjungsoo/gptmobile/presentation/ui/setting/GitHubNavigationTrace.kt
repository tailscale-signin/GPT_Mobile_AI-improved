package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.Trace
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.concurrent.atomic.AtomicInteger

internal data class GitHubCompositionTiming(val trace: GitHubNavigationTrace, val cached: Boolean)
internal val LocalGitHubCompositionTiming = staticCompositionLocalOf<GitHubCompositionTiming?> { null }

/** Composition-origin spans, explicitly distinct from a full navigation-action benchmark. */
internal class GitHubNavigationTrace {
    private val cookie = nextCookie.incrementAndGet()
    private val started = System.nanoTime()
    private var framePending = true
    private var composerPending = true

    init {
        Trace.beginAsyncSection(FIRST_FRAME, cookie)
        Trace.beginAsyncSection(COMPOSER, cookie)
    }

    fun firstUsefulFrame(cached: Boolean) {
        if (!framePending) return
        framePending = false
        Trace.endAsyncSection(FIRST_FRAME, cookie)
        record("FIRST_USEFUL_FRAME", cached)
    }

    fun composerReady(cached: Boolean) {
        if (!composerPending) return
        composerPending = false
        Trace.endAsyncSection(COMPOSER, cookie)
        record("COMPOSER_READY", cached)
    }

    fun close() {
        if (framePending) Trace.endAsyncSection(FIRST_FRAME, cookie)
        if (composerPending) Trace.endAsyncSection(COMPOSER, cookie)
        framePending = false
        composerPending = false
    }

    private fun record(event: String, cached: Boolean) = AppLogRecorder.record(
        "GitHubNavigation",
        "$event · origin=composition_started · elapsedMillis=${(System.nanoTime() - started).coerceAtLeast(0) / 1_000_000} · cached=$cached"
    )

    companion object {
        private val nextCookie = AtomicInteger()
        const val FIRST_FRAME = "GitHub/composition/firstUsefulFrame"
        const val COMPOSER = "GitHub/composition/composerReady"
    }
}
