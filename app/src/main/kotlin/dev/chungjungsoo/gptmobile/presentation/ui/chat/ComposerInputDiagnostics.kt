package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.util.concurrent.atomic.AtomicInteger

/** Bounded observations of an editor capture lifetime; never text, keys or private identifiers. */
class ComposerInputDiagnostics internal constructor(
    private val generation: Int,
    private val originIsCurrent: () -> Boolean,
    private val publish: (Map<String, Any?>, Long) -> Unit,
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long = System::currentTimeMillis
) {
    internal enum class LifecycleState { INITIALIZED, CREATED, STARTED, RESUMED, DESTROYED }
    internal enum class InputMode { TOUCH, KEYBOARD }
    internal enum class Release { SEND, SHORTCUT, BACKGROUND_TAP, DISABLED, LIFECYCLE_STOP, DISPOSE }
    internal enum class LifecycleEvent(val state: LifecycleState) {
        CREATE(LifecycleState.CREATED),
        START(LifecycleState.STARTED),
        RESUME(LifecycleState.RESUMED),
        PAUSE(LifecycleState.STARTED),
        STOP(LifecycleState.CREATED),
        DESTROY(LifecycleState.DESTROYED)
    }

    internal data class State(
        val focused: Boolean,
        // Window-wide inset visibility is not proof that this editor owns the IME.
        val imeVisible: Boolean,
        val enabled: Boolean,
        val windowFocused: Boolean,
        val lifecycle: LifecycleState,
        val inputMode: InputMode
    )

    private data class Event(val name: String, val elapsedMillis: Long)
    private val events = ArrayDeque<Event>()
    private var state: State? = null
    private var retired = false
    private var focusTransitions = 0
    private var imeTransitions = 0
    private var dropped = 0
    private var lastElapsed: Long? = null

    init {
        require(generation in 1..MAX_GENERATION)
    }

    @Synchronized
    internal fun attach(initial: State) {
        if (state != null || !authorized()) return
        state = initial
        record("ATTACHED")
    }

    @Synchronized
    internal fun observe(observed: State) {
        if (state == null || !authorized()) return
        // A snapshot may coalesce platform changes. Equal-age events below mean
        // observed changes, not a claimed causal ordering of hidden IME events.
        val prior = requireNotNull(state)
        val changes = buildList {
            if (prior.focused != observed.focused) {
                focusTransitions = (focusTransitions + 1).coerceAtMost(255)
                add(if (observed.focused) "EDITOR_FOCUSED" else "EDITOR_UNFOCUSED")
            }
            if (prior.imeVisible != observed.imeVisible) {
                imeTransitions = (imeTransitions + 1).coerceAtMost(255)
                add(if (observed.imeVisible) "IME_VISIBLE" else "IME_HIDDEN")
            }
            if (prior.enabled != observed.enabled) add(if (observed.enabled) "CHAT_ENABLED" else "CHAT_DISABLED")
            if (prior.windowFocused != observed.windowFocused) add(if (observed.windowFocused) "WINDOW_FOCUSED" else "WINDOW_UNFOCUSED")
            if (prior.inputMode != observed.inputMode) add(if (observed.inputMode == InputMode.TOUCH) "INPUT_TOUCH" else "INPUT_KEYBOARD")
        }
        // Lifecycle callbacks carry the event as well as its target state.
        state = observed.copy(lifecycle = prior.lifecycle)
        if (changes.isNotEmpty()) record(changes)
    }

    @Synchronized
    internal fun lifecycle(event: LifecycleEvent, observedState: LifecycleState = event.state) {
        if (state == null || !authorized()) return
        state = requireNotNull(state).copy(lifecycle = observedState)
        record("LIFECYCLE_${event.name}")
    }

    @Synchronized
    internal fun release(actor: Release) {
        if (state != null && authorized()) record("RELEASE_${actor.name}")
    }

    @Synchronized
    internal fun detach() {
        if (state != null && authorized()) record("DETACHED")
        retire()
    }

    private fun authorized(): Boolean {
        if (retired) return false
        if (originIsCurrent()) return true
        retire()
        return false
    }

    private fun retire() {
        retired = true
        state = null
        events.clear()
    }

    private fun record(name: String) = record(listOf(name))

    private fun record(names: List<String>) {
        if (!authorized()) return
        val observed = requireNotNull(state)
        val now = elapsedMillis().let { sampled -> lastElapsed?.let { sampled.coerceAtLeast(it) } ?: sampled }
        lastElapsed = now
        while (events.isNotEmpty() && now - events.first().elapsedMillis > MAX_AGE_MILLIS) dropFirst()
        names.forEach { events.addLast(Event(it, now)) }
        while (events.size > MAX_EVENTS) dropFirst()
        val fields = buildMap<String, Any?> {
            put("diagnostic_area", "APP")
            put("diagnostic_capture_mode", "SELECTED_CAPTURE")
            put("composer_generation", generation)
            put("composer_events_dropped", dropped)
            put("composer_focus_transitions", focusTransitions)
            put("composer_ime_transitions", imeTransitions)
            put("composer_focused", observed.focused)
            put("composer_ime_visible", observed.imeVisible)
            put("composer_enabled", observed.enabled)
            put("composer_window_focused", observed.windowFocused)
            put("composer_lifecycle", observed.lifecycle.name)
            put("composer_input_mode", observed.inputMode.name)
            events.forEachIndexed { index, event ->
                put("composer_event_${index + 1}", event.name)
                put("composer_event_${index + 1}_age_ms", (now - event.elapsedMillis).coerceIn(0, MAX_AGE_MILLIS))
            }
        }
        // The sink rechecks the same origin atomically with retaining the snapshot.
        publish(fields, wallMillis())
    }

    private fun dropFirst() {
        events.removeFirst()
        dropped = (dropped + 1).coerceAtMost(MAX_GENERATION)
    }

    internal companion object {
        const val MAX_EVENTS = 8
        const val MAX_AGE_MILLIS = 600_000L
        const val MAX_GENERATION = 16_000_000
        private val generations = AtomicInteger()

        fun nextGeneration(): Int? {
            val prior = generations.getAndUpdate { if (it < MAX_GENERATION) it + 1 else it }
            return (prior + 1).takeIf { prior < MAX_GENERATION }
        }
    }
}
