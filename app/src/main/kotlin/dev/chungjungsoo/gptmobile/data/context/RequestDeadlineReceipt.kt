package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.Serializable

/** Elapsed durations from a monotonic clock; never interpreted as wall-clock timestamps. */
@Serializable
data class RequestDeadlineReceipt(
    val schemaVersion: Int = 1,
    val queueAgeMillis: Long?,
    val elapsedMillis: Long,
    val remainingMillis: Long,
    val requestLimit: Int,
    val requestsReserved: Int,
    val cancellationSupport: String = "coroutine_and_http_call_cancellation",
    val remoteCleanupConfirmation: String = "not_implied_by_local_cancellation"
)
