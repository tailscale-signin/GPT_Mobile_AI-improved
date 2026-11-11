package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.channels.Channel

/** A conflated wake-up never owns log text. Both pending lanes have explicit payload and count limits. */
internal class BoundedDiagnosticPendingQueue<T>(
    private val maximumCharacters: Int = 256 * 1024,
    private val reservedCriticalCharacters: Int = 64 * 1024,
    private val maximumRecords: Int = 512,
    private val reservedCriticalRecords: Int = 128,
    private val dropped: (critical: Boolean) -> Unit = {}
) {
    private data class Entry<T>(val value: T, val characters: Int)
    private val lock = Any()
    private val ordinary = ArrayDeque<Entry<T>>()
    private val critical = ArrayDeque<Entry<T>>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var ordinaryCharacters = 0
    private var criticalCharacters = 0
    val size: Int get() = synchronized(lock) { ordinary.size + critical.size }
    val retainedCharacters: Int get() = synchronized(lock) { ordinaryCharacters + criticalCharacters }

    init {
        require(reservedCriticalCharacters in 1 until maximumCharacters)
        require(reservedCriticalRecords in 1 until maximumRecords)
    }

    fun offer(value: T, characters: Int, isCritical: Boolean): Boolean {
        require(characters > 0)
        val accepted = synchronized(lock) {
            val lane = if (isCritical) critical else ordinary
            val characterLimit = if (isCritical) reservedCriticalCharacters else maximumCharacters - reservedCriticalCharacters
            val countLimit = if (isCritical) reservedCriticalRecords else maximumRecords - reservedCriticalRecords
            if (characters > characterLimit) {
                dropped(isCritical)
                return@synchronized false
            }
            fun retained() = if (isCritical) criticalCharacters else ordinaryCharacters
            while (lane.size >= countLimit || retained() > characterLimit - characters) {
                val removed = lane.removeFirst()
                if (isCritical) criticalCharacters -= removed.characters else ordinaryCharacters -= removed.characters
                dropped(isCritical)
            }
            lane.addLast(Entry(value, characters))
            if (isCritical) criticalCharacters += characters else ordinaryCharacters += characters
            true
        }
        if (accepted) wakeups.trySend(Unit)
        return accepted
    }

    /** Wait without removing records; the consumer drains only after it holds the disk/snapshot lock. */
    suspend fun awaitRecords() {
        while (synchronized(lock) { ordinary.isEmpty() && critical.isEmpty() }) wakeups.receive()
    }

    fun poll(): T? = synchronized(lock) { removeFirst()?.value }

    /** A capture copies the same bounded lane order without stealing records from the live writer. */
    fun snapshot(): List<T> = synchronized(lock) { critical.map { it.value } + ordinary.map { it.value } }

    fun drainBatch(maximumBatchRecords: Int = 64, maximumBatchCharacters: Int = 32 * 1024): List<T> = synchronized(lock) {
        require(maximumBatchRecords > 0 && maximumBatchCharacters > 0)
        val result = ArrayList<T>(minOf(maximumBatchRecords, ordinary.size + critical.size))
        var characters = 0
        while (result.size < maximumBatchRecords) {
            val next = critical.firstOrNull() ?: ordinary.firstOrNull() ?: break
            if (result.isNotEmpty() && next.characters > maximumBatchCharacters - characters) break
            result += requireNotNull(removeFirst()).value
            characters += next.characters
        }
        result
    }

    /** Deliberate privacy clearing is not an overflow drop. */
    fun clear() = synchronized(lock) {
        ordinary.clear()
        critical.clear()
        ordinaryCharacters = 0
        criticalCharacters = 0
    }

    private fun removeFirst(): Entry<T>? = when {
        critical.isNotEmpty() -> critical.removeFirst().also { criticalCharacters -= it.characters }
        ordinary.isNotEmpty() -> ordinary.removeFirst().also { ordinaryCharacters -= it.characters }
        else -> null
    }
}
