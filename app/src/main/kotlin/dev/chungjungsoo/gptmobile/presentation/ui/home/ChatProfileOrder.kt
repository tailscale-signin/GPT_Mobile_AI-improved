package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Preserve selection indices and user ordering within each favorite group. */
internal fun orderedChatProfiles(platforms: List<PlatformV2>): List<Pair<Int, PlatformV2>> = platforms
    .mapIndexed { index, platform -> index to platform }
    .filter { it.second.enabled }
    .sortedByDescending { it.second.isFavorite }

/** Stable partition after user filters/sort. No persisted order or selection is rewritten. */
internal fun onlineFirstChatProfiles(sortedMatches: List<PlatformV2>, onlineUids: Set<String>): List<PlatformV2> =
    sortedMatches.filter { it.uid in onlineUids } + sortedMatches.filterNot { it.uid in onlineUids }

/** Keep hit targets/focus still during interaction while removing rows that no longer match. */
internal fun deferredChatProfileOrder(
    desired: List<PlatformV2>,
    displayedUids: List<String>,
    interactionActive: Boolean
): List<PlatformV2> {
    if (!interactionActive || displayedUids.isEmpty()) return desired
    val remaining = desired.associateByTo(linkedMapOf()) { it.uid }
    return displayedUids.mapNotNull { remaining.remove(it) } + remaining.values
}
