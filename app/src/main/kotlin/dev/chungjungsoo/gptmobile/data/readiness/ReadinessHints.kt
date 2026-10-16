package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI sends only focus and an empty/non-empty bit; prewarm never sees draft text. */
enum class ComposerActivity { IDLE, FOCUSED, TYPING }

object ReadinessHints {
    private val network = MutableStateFlow(0L)
    val networkEpoch = network.asStateFlow()

    @Synchronized
    fun networkChanged() {
        network.value++
    }

    private val ready = MutableStateFlow(false)
    private val selected = MutableStateFlow<List<String>>(emptyList())
    private val activity = MutableStateFlow(ComposerActivity.IDLE)
    private val engaged = MutableStateFlow(false)
    private var focused = false
    private var hasDraft = false
    val uiReady = ready.asStateFlow()
    val profiles = selected.asStateFlow()
    val composerActivity = activity.asStateFlow()
    val composerEngaged = engaged.asStateFlow()

    fun usableUi() {
        ready.value = true
    }

    fun selectProfiles(ids: List<String>) {
        selected.value = ids.distinct().take(4)
    }

    @Synchronized
    fun composerFocusChanged(value: Boolean) {
        focused = value
        publishActivity()
    }

    @Synchronized
    fun composerDraftChanged(nonEmpty: Boolean) {
        hasDraft = nonEmpty
        publishActivity()
    }

    @Synchronized
    fun clearComposer() {
        focused = false
        hasDraft = false
        publishActivity()
    }

    private fun publishActivity() {
        // Typing after focus is NOT another warmup trigger: keep in-flight socket work alive.
        engaged.value = focused
        activity.value = when {
            !focused -> ComposerActivity.IDLE
            hasDraft -> ComposerActivity.TYPING
            else -> ComposerActivity.FOCUSED
        }
    }
}
