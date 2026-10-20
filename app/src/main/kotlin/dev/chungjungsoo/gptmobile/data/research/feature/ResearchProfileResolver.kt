package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig

internal object ResearchProfileResolver {
    fun examinerUid(features: AppFeatureSettings, chatId: Int, tools: ChatMcpToolConfig, override: String = ""): String =
        override.ifBlank {
            features.conversationResearch[chatId]?.crawlerProfileUid.orEmpty().ifBlank {
                tools.research?.crawlerProfileUid.orEmpty().ifBlank { features.researchFeature.crawlerProfileUid.orEmpty() }
            }
        }

    fun dedicatedReviewer(features: AppFeatureSettings, profiles: List<PlatformV2>, appraiser: PlatformV2): PlatformV2? {
        val selected = features.researchFeature.reviewerProfileUid ?: return null
        return profiles.singleOrNull { it.uid == selected && it.enabled }?.takeIf {
            dev.chungjungsoo.gptmobile.data.delegation.CanonicalModelIdentity.independence(it, appraiser) == dev.chungjungsoo.gptmobile.data.delegation.ModelIndependence.DIFFERENT
        }
    }
}
