package dev.chungjungsoo.gptmobile.data.readiness

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Speculation uses separate bounded network/local lanes; accepted Send takes neither permit. */
enum class ReadinessResource { NETWORK, LOCAL }

@Singleton
class ReadinessScheduler @Inject constructor(@dev.chungjungsoo.gptmobile.di.ReadinessScope private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val network = Semaphore(2)
    private val local = Semaphore(1)

    fun request(key: String, eligible: () -> Boolean, work: suspend () -> Unit) {
        requestFor(key, eligible, ReadinessResource.NETWORK, work)
    }

    fun requestFor(key: String, eligible: () -> Boolean, resource: ReadinessResource, work: suspend () -> Unit) {
        synchronized(jobs) {
            if (jobs[key]?.isActive == true || jobs.size >= 12 || !eligible()) return
            val lane = if (resource == ReadinessResource.LOCAL) local else network
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    lane.withPermit { if (eligible()) work() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Optional preparation failure does not turn into a Send error.
                }
            }
            jobs[key] = job
            job.invokeOnCompletion { jobs.remove(key, job) }
            job.start()
        }
    }

    fun cancelSpeculation() {
        val cancelled = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        // Cancellation cleanup may be dispatched later. Old jobs must not consume the
        // next generation's admission slots; completion still removes by job identity.
        cancelled.forEach { it.cancel() }
    }
}
