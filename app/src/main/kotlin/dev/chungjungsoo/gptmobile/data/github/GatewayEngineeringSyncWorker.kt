package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded observation of gateway-owned work, subject to Android background scheduling. */
@HiltWorker
class GatewayEngineeringSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val engineering: GatewayEngineeringRepository
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val started = System.nanoTime()
        val synchronized = withTimeoutOrNull(GatewayObservationPolicy.SLICE_MILLIS) {
            dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
            engineering.syncAll()
        } == true
        val next = GatewayObservationPolicy.next(synchronized, runAttemptCount)
        val generation = inputData.getString(GatewayEngineeringRepository.OBSERVER_GENERATION_KEY)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "unavailable"
        AppLogRecorder.record("GatewayObserver", "OBSERVATION_SLICE · workId=$id · generation=$generation · attempt=$runAttemptCount · outcome=$next · owner=GATEWAY · elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        return when (next) {
            GatewayObservationPolicy.Next.OBSERVED -> Result.success()
            GatewayObservationPolicy.Next.RETRY -> Result.retry()
            // The durable job and its cursor remain authoritative. The next
            // periodic slice or explicit UI reconnect observes that same job.
            GatewayObservationPolicy.Next.KEEP_CHECKPOINT -> Result.success(androidx.work.workDataOf("observation" to "PENDING", "executionOwner" to "GATEWAY"))
        }
    }
}
