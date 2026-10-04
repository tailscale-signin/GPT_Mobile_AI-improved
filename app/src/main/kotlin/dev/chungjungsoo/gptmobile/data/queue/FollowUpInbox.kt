package dev.chungjungsoo.gptmobile.data.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Admission runs for the whole turn, including slow research, independently of model requests. */
class FollowUpInbox(
    scope: CoroutineScope,
    pending: Flow<List<PendingPrompt>>,
    private val eligible: suspend (PendingPrompt) -> Boolean,
    private val accept: suspend (PendingPrompt) -> Boolean,
    private val progress: (PendingPrompt, FollowUpPhase?, Long?) -> Unit = { _, _, _ -> },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val deadlines = mutableMapOf<String, Long>()
    private val drafts = mutableMapOf<String, PendingPrompt>()
    private val accepted = MutableStateFlow<List<PendingPrompt>>(emptyList())
    private val waiting = MutableStateFlow(false)
    private val watcher: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            pending.collectLatest { prompts ->
                val ids = prompts.map { it.id }.toSet()
                drafts.keys.filter { it !in ids }.toList().forEach { id ->
                    drafts.remove(id)?.let { progress(it, null, null) }
                    deadlines.remove(id)
                }
                val candidates = prompts.take((3 - accepted.value.size).coerceAtLeast(0)).takeWhile { !it.paused && eligible(it) }
                drafts.keys.filter { id -> candidates.none { it.id == id } }.toList().forEach { id ->
                    drafts.remove(id)?.let { progress(it, null, null) }
                    deadlines.remove(id)
                }
                candidates.forEach { prompt ->
                    val deadline = deadlines.getOrPut(prompt.id) { nowMs() + GRACE_MS }
                    drafts[prompt.id] = prompt
                    progress(prompt, FollowUpPhase.COUNTDOWN, deadline)
                }
                waiting.value = candidates.isNotEmpty()
                for (prompt in candidates) {
                    delay((deadlines.getValue(prompt.id) - nowMs()).coerceAtLeast(0L))
                    currentCoroutineContext().ensureActive()
                    // Never lose an admitted message if Room invalidates the pending flow
                    // between committing the transaction and publishing the handoff.
                    withContext(NonCancellable) {
                        if (eligible(prompt) && accept(prompt)) {
                            accepted.value += prompt
                            progress(prompt, FollowUpPhase.MERGING, null)
                        } else {
                            progress(prompt, null, null)
                        }
                    }
                }
                waiting.value = false
            }
        } finally {
            waiting.value = false
            drafts.values.forEach { progress(it, null, null) }
        }
    }

    fun snapshot(): List<PendingPrompt> = accepted.value

    /** Keep a short primary answer alive until its already-visible countdown settles. */
    suspend fun awaitCountdown() { waiting.first { !it } }

    suspend fun close() { watcher.cancelAndJoin() }

    companion object {
        const val GRACE_MS = 3_000L
    }
}
