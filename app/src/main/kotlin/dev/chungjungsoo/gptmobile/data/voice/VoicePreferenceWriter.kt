package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A slow older write must finish before the newest settings can be persisted. */
internal class VoicePreferenceWriter<T>(scope: CoroutineScope, write: suspend (T) -> Unit) {
    private val pending = Channel<T>(Channel.CONFLATED)

    init {
        val job = scope.launch { for (value in pending) write(value) }
        job.invokeOnCompletion { pending.close() }
    }

    fun save(value: T) {
        pending.trySend(value)
    }
}
