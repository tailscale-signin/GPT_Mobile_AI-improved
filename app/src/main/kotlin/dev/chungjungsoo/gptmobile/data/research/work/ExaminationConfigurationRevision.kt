package dev.chungjungsoo.gptmobile.data.research.work

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Retry blocked jobs only when execution configuration changes, never for telemetry or appearance. */
internal fun examinationConfigurationRevision(features: AppFeatureSettings, profiles: List<PlatformV2>): String = researchHash(
    Json.encodeToString(
        listOf(
            Json.encodeToString(features.researchFeature.copy(articleCards = true, compactCards = true, showWarnings = true, showMethodMetrics = true, automaticOrganization = true, archivePreflight = true, researchMemory = false, shareResearchRecall = false)),
            Json.encodeToString(features.conversationResearch.toSortedMap().toMap()),
            profiles.sortedBy { it.uid }.joinToString("|") {
                ConfiguredDispatchAuthority.profileRevision(it) + ConfiguredDispatchAuthority.policyRevision(features, it.uid, null)
            }
        )
    )
)
