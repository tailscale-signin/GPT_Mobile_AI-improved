package dev.chungjungsoo.gptmobile.data.research.academic

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Physical dispatch spacing is shared by every contributor, including redirect and retry routes. */
internal class AcademicServiceLimiter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }, private val wait: suspend (Long) -> Unit = { delay(it) }) {
    private class Gate(val mutex: Mutex = Mutex(), var last: Long? = null)
    private val gates = ConcurrentHashMap<String, Gate>()
    suspend fun await(host: String, allowed: suspend () -> Boolean) {
        val normalizedHost = host.lowercase(java.util.Locale.ROOT)
        val service = if (normalizedHost == "ncbi.nlm.nih.gov" || normalizedHost.endsWith(".ncbi.nlm.nih.gov")) "ncbi" else normalizedHost
        val spacing = when {
            service == "ncbi" -> 350L
            service == "export.arxiv.org" || service == "arxiv.org" -> 3000L
            else -> 250L
        }
        val gate = gates.computeIfAbsent(service) { Gate() }
        gate.mutex.withLock {
            check(allowed()) { "permission_denied" }
            gate.last?.let { wait((spacing - (clock() - it)).coerceIn(0, spacing)) }
            check(allowed()) { "permission_denied" }
            gate.last = clock()
        }
    }
    companion object {
        val shared = AcademicServiceLimiter()
    }
}
