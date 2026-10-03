package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

/** Optional capabilities are explicit for each profile, including restored legacy profiles. */
@Serializable
data class ProfileBehaviorSettings(
    val delegationEnabled: Boolean = false,
    val crawlersEnabled: Boolean = false,
    val crawlerToolIds: Set<String> = emptySet(),
    val maxCrawlPages: Int = 5
) {
    fun normalized() = copy(maxCrawlPages = maxCrawlPages.coerceIn(1, 20))
}

fun AppFeatureSettings.delegationFor(profileUid: String): ModelDelegationSettings =
    delegation.copy(enabled = delegation.enabled && profileBehavior[profileUid]?.delegationEnabled == true)
