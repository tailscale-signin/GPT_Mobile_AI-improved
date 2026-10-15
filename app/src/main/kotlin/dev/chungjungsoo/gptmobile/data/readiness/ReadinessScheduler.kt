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

/** No caller joins speculative cancellation. Accepted sends never acquire these permits. */
@Singleton
class ReadinessScheduler @Inject constructor(@dev.chungjungsoo.gptmobile.di.ReadinessScope private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val network = Semaphore(2)
    fun request(key: String, eligible: () -> Boolean, work: suspend () -> Unit) {
        synchronized(jobs) {
            if (jobs[key]?.isActive == true || jobs.size >= 8 || !eligible()) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    network.withPermit { if (eligible()) work() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Quiet metadata failure; required acquisition retains its own visible error.
                }
            }
            jobs[key] = job
            job.invokeOnCompletion { jobs.remove(key, job) }
            job.start()
        }
    }
    fun cancelSpeculation() {
        jobs.values.toList().forEach { it.cancel() }
    }
}
