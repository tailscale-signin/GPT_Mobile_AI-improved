package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardClient
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardMonitor
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardState
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayDashboardTarget
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

@HiltViewModel
internal class GitHubGatewayStatusViewModel @Inject constructor(private val settings: SettingRepository, private val client: GatewayDashboardClient) : ViewModel() {
    private val monitor = GatewayDashboardMonitor(client)
    private val mutable = MutableStateFlow(GatewayDashboardState())
    val state = mutable.asStateFlow()

    fun refresh() {
        client.refresh()
        monitor.refresh()
    }

    suspend fun observe(profileUid: String) {
        combine(settings.observePlatformV2s(), settings.observeFeatureSettings()) { profiles, features ->
            gatewayDashboardTarget(profileUid, profiles, features.githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid)
        }.distinctUntilChanged().collectLatest { target ->
            monitor.observe(target, mutable.value) { mutable.value = it }
        }
    }
}
