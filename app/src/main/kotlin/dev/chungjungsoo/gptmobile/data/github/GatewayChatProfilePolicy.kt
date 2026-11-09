package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.gateway.validGatewayAvailabilityModel
import java.net.URI

internal object GatewayChatProfilePolicy {
    fun configured(profile: PlatformV2): Boolean = profile.uid.isNotBlank() &&
        GitHubAutonomousEngineeringPolicy.supportsSavedJobs(profile) &&
        validGatewayAvailabilityModel(profile.model) &&
        runCatching {
            val uri = URI(profile.apiUrl.trimEnd('/'))
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false)

    fun eligible(profiles: List<PlatformV2>, knownGatewayProfileUids: Set<String>): List<PlatformV2> = profiles.filter { profile ->
        profile.uid in knownGatewayProfileUids && profiles.count { it.uid == profile.uid } == 1 && configured(profile)
    }
}
