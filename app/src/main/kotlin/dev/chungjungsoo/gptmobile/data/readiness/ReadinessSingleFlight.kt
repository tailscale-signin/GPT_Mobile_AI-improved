package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Producer belongs to its scope, consumers own detachable leases. No I/O runs under the map lock. */
internal class ReadinessSingleFlight<K, V>(private val scope: CoroutineScope) {
    private class Flight<V>(val result: Deferred<V>, var consumers: Int = 0)
    private val flights = mutableMapOf<K, Flight<V>>()
    suspend fun acquire(key: K, produce: suspend () -> V): V {
        val flight = synchronized(flights) {
            val existing = flights[key]
            val selected = existing ?: Flight(scope.async(start = CoroutineStart.LAZY) { produce() }).also { flights[key] = it }
            selected.consumers++
            selected.result.start()
            selected
        }
        try {
            return flight.result.await()
        } finally {
            val abandoned = synchronized(flights) {
                flight.consumers--
                if (flight.consumers == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    true
                } else {
                    false
                }
            }
            if (abandoned && !flight.result.isCompleted) flight.result.cancel()
        }
    }
    fun invalidate() {
        val old = synchronized(flights) { flights.values.toList().also { flights.clear() } }
        old.forEach { it.result.cancel() }
    }
}
