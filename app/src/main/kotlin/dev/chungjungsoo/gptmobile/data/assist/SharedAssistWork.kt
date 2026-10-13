package dev.chungjungsoo.gptmobile.data.assist

import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** A physical producer belongs to its authorized subscribers, never to the first caller's Job. */
internal class SharedAssistWork<T> {
    internal class Subscriber(
        val allowed: suspend () -> Boolean,
        val status: suspend (AssistTaskState) -> Unit,
        val notice: suspend (String) -> Unit
    ) {
        val id: String = UUID.randomUUID().toString()
    }

    internal inner class Flight(context: CoroutineContext, private val workId: String) {
        private val scope = CoroutineScope(context.minusKey(Job) + SupervisorJob())
        private val subscribers = linkedMapOf<String, Subscriber>()
        lateinit var result: Deferred<T>

        fun start(producer: suspend (Flight) -> T) {
            result = scope.async(start = CoroutineStart.LAZY) { producer(this@Flight) }
            result.invokeOnCompletion { scope.cancel() }
        }

        fun add(subscriber: Subscriber) = synchronized(subscribers) { subscribers[subscriber.id] = subscriber }
        fun remove(subscriber: Subscriber): Boolean = synchronized(subscribers) {
            subscribers.remove(subscriber.id)
            subscribers.isEmpty()
        }
        fun contains(subscriber: Subscriber): Boolean = synchronized(subscribers) { subscriber.id in subscribers }
        private fun snapshot(): List<Subscriber> = synchronized(subscribers) { subscribers.values.toList() }

        suspend fun hasAuthorizedSubscriber(): Boolean {
            for (subscriber in snapshot()) {
                try {
                    if (subscriber.allowed()) return true
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    remove(subscriber)
                }
            }
            return false
        }

        suspend fun publish(state: AssistTaskState) = deliver { it.status(state.copy(sharedWorkId = workId, subscriberId = it.id)) }
        suspend fun notify(message: String) = deliver { it.notice(message) }

        private suspend fun deliver(action: suspend (Subscriber) -> Unit) {
            for (subscriber in snapshot()) {
                try {
                    if (subscriber.allowed()) action(subscriber)
                } catch (cancelled: CancellationException) {
                    // An ended consumer channel cannot cancel the independent producer.
                    currentCoroutineContext().ensureActive()
                    remove(subscriber)
                } catch (_: ClosedSendChannelException) {
                    remove(subscriber)
                }
            }
        }
    }

    private val flights = mutableMapOf<String, Flight>()

    suspend fun await(key: String, subscriber: Subscriber, producer: suspend (Flight) -> T): T? {
        if (!subscriber.allowed()) return null
        val context = currentCoroutineContext()
        val flight = synchronized(flights) {
            flights.getOrPut(key) { Flight(context, key).also { it.start(producer) } }.also { it.add(subscriber) }
        }
        flight.result.start()
        return try {
            while (context.isActive && flight.contains(subscriber) && subscriber.allowed()) {
                // Recheck a revoked subscriber even while another subscriber still owns inference.
                val completed = withTimeoutOrNull(100L) { Result.success(flight.result.await()) }
                if (completed != null) return if (flight.contains(subscriber) && subscriber.allowed()) completed.getOrThrow() else null
            }
            context.ensureActive()
            null
        } finally {
            synchronized(flights) {
                if (flight.remove(subscriber)) {
                    if (flights[key] === flight) flights.remove(key)
                    flight.result.cancel()
                }
            }
        }
    }
}
