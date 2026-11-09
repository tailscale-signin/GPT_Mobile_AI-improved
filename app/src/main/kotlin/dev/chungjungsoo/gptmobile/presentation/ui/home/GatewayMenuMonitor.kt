package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.github.GatewayKnownProfileStore
import dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Process-scoped passive metadata. Android may stop this process; no periodic-work deadline is promised. */
@Singleton
internal class GatewayMenuMonitor internal constructor(source: Flow<List<GatewayMenuConnection>>, scope: CoroutineScope) {
    @Inject constructor(settings: SettingRepository, repository: GatewayAvailabilityRepository, scopes: GatewayVerifiedScopeStore, knownProfiles: GatewayKnownProfileStore) :
        this(gatewayMenuConnections(settings, repository, scopes, knownProfiles), CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val current = MutableStateFlow<List<GatewayMenuConnection>>(emptyList())
    val connections = current.asStateFlow()

    init {
        scope.launch {
            source.retryWhen { failure, attempt ->
                if (failure is CancellationException) throw failure
                // Keep navigation/history context, but never retain a fresh readiness
                // claim while a settings or stored-scope read cannot be refreshed.
                current.update { rows -> rows.map { it.copy(availability = it.availability.unverified()) } }
                delay((1_000L shl attempt.coerceAtMost(8).toInt()).coerceAtMost(180_000L))
                true
            }.collect { current.value = it }
        }
    }
}
