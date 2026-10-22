package dev.chungjungsoo.gptmobile.data.research

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** AtomicFile.openRead can restore backups: readers and writers must share the same file lock. */
internal object ResearchAtomicFiles {
    private val locks = Array(256) { ReentrantLock() }
    fun <T> locked(file: File, block: () -> T): T = locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size].withLock(block)
}
