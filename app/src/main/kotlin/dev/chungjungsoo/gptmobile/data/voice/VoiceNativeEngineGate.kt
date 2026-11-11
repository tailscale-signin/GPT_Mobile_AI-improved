package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Worker-thread gate: a speech decoder must release before another engine initializes. */
internal object VoiceNativeEngineGate {
    private val lock = ReentrantLock(true)
    fun <T> exclusive(block: () -> T): T = lock.withLock(block)
}
