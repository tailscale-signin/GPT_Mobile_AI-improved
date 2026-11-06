package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class DiagnosticExportBusy : IOException("Another diagnostic export is already running. Wait for it to finish before retrying.")

/** Shared by conversation and settings exports; fail fast instead of queuing stale snapshots. */
object DiagnosticExportGate {
    private val active = AtomicBoolean(false)

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[Permit] != null) return block()
        return acquire().use { withContext(Permit()) { block() } }
    }

    private class Permit : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }

    fun acquire(): AutoCloseable {
        if (!active.compareAndSet(false, true)) throw DiagnosticExportBusy()
        val held = AtomicBoolean(true)
        return AutoCloseable { if (held.compareAndSet(true, false)) active.set(false) }
    }
}
