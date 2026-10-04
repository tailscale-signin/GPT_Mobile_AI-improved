package dev.chungjungsoo.gptmobile.data.queue

import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.yield

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
    private val waiting = MutableStateFlow(true)
    private val watcher: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            pending.collectLatest { prompts ->
                waiting.value = true
                val observedAtMs = nowMs()
                val ids = prompts.map { it.id }.toSet()
                drafts.keys.filter { it !in ids }.toList().forEach { id ->
                    drafts.remove(id)?.let { progress(it, null, null) }
                    deadlines.remove(id)
                }
                val candidates = prompts.take((3 - accepted.value.size).coerceAtLeast(0)).takeWhile { !it.paused && canAccept(it) }
                drafts.keys.filter { id -> candidates.none { it.id == id } }.toList().forEach { id ->
                    drafts.remove(id)?.let { progress(it, null, null) }
                    deadlines.remove(id)
                }
                candidates.forEach { prompt ->
                    val deadline = deadlines.getOrPut(prompt.id) { observedAtMs + GRACE_MS }
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
                        val consumed = try {
                            canAccept(prompt) && accept(prompt)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (consumed) {
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

    private suspend fun canAccept(prompt: PendingPrompt): Boolean = try {
        eligible(prompt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    fun snapshot(): List<PendingPrompt> = accepted.value

    /** Keep a short primary answer alive until its already-visible countdown settles. */
    suspend fun awaitCountdown() {
        // Let a just-enqueued emission enter admission before deciding the turn is idle.
        yield()
        waiting.first { !it }
    }

    suspend fun close() {
        watcher.cancelAndJoin()
    }

    companion object {
        const val GRACE_MS = 3_000L
    }
}
