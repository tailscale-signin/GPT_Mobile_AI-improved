package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes native inference, stream teardown and model construction; idle resident models may coexist. */
internal object VoiceNativeEngineGate {
    private val lock = ReentrantLock(true)
    fun <T> exclusive(block: () -> T): T = lock.withLock(block)
}
