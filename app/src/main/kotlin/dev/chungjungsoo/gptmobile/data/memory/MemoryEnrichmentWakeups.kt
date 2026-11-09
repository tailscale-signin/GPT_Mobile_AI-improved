package dev.chungjungsoo.gptmobile.data.memory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** A persisted cooldown gets one expiry signal even if the runtime remains unchanged. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Long>.withEnrichmentCooldownExpiry(now: () -> Long = System::currentTimeMillis): Flow<Unit> =
    distinctUntilChanged().transformLatest { until ->
        emit(Unit)
        val remaining = (until - now()).coerceAtLeast(0L)
        if (remaining > 0) {
            delay(remaining)
            emit(Unit)
        }
    }
