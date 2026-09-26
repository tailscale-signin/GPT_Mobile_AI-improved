package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Preserve selection indices and user ordering within each favorite group. */
internal fun orderedChatProfiles(platforms: List<PlatformV2>): List<Pair<Int, PlatformV2>> = platforms
    .mapIndexed { index, platform -> index to platform }
    .filter { it.second.enabled }
    .sortedByDescending { it.second.isFavorite }
