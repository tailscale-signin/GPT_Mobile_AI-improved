package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig

enum class ExaminerSelectionStatus { READY, UNSELECTED, MISSING, DISABLED, AMBIGUOUS }
data class ExaminerSelection(val status: ExaminerSelectionStatus, val profileUid: String = "", val profile: PlatformV2? = null)

internal object ResearchProfileResolver {
    fun examinerSelection(features: AppFeatureSettings, profiles: List<PlatformV2>, chatId: Int, tools: ChatMcpToolConfig, override: String = ""): ExaminerSelection {
        val uid = examinerUid(features, chatId, tools, override)
        if (uid.isBlank()) return ExaminerSelection(ExaminerSelectionStatus.UNSELECTED)
        val matches = profiles.filter { it.uid == uid }
        if (matches.isEmpty()) return ExaminerSelection(ExaminerSelectionStatus.MISSING, uid)
        if (matches.size > 1) return ExaminerSelection(ExaminerSelectionStatus.AMBIGUOUS, uid)
        val profile = matches.single()
        return ExaminerSelection(if (profile.enabled) ExaminerSelectionStatus.READY else ExaminerSelectionStatus.DISABLED, uid, profile.takeIf { it.enabled })
    }

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
