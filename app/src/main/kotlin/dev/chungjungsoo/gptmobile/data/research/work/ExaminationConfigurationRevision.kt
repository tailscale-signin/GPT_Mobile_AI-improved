package dev.chungjungsoo.gptmobile.data.research.work

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Only examiner/reviewer, retrieval consent and execution capacity changes wake a blocked job. */
internal fun examinationConfigurationRevision(features: AppFeatureSettings, profiles: List<PlatformV2>, chatId: Int? = null, profileUid: String? = null, tools: ChatMcpToolConfig = ChatMcpToolConfig()): String {
    val selected = buildSet {
        profileUid?.takeIf { it.isNotBlank() }?.let(::add)
        if (chatId != null) {
            ResearchProfileResolver.examinerUid(features, chatId, tools).takeIf { it.isNotBlank() }?.let(::add)
        } else {
            features.researchFeature.crawlerProfileUid?.let(::add)
            features.conversationResearch.values.mapNotNull { it.crawlerProfileUid }.forEach(::add)
        }
        features.researchFeature.reviewerProfileUid?.let(::add)
        profiles.filter { it.uid in this }.forEach { profile ->
            features.delegationFor(profile.uid).takeIf { it.reviewerEnabled }?.reviewerProfileUid?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
    val relevant = profiles.filter { it.uid in selected }.sortedBy { it.uid }
    return researchHash(
        buildJsonObject {
            val policy = features.researchFeature
            put("selection", JsonArray(selected.sorted().map(::JsonPrimitive)))
            put("enabled", policy.enabled)
            put("mode", policy.appraisalMode.name)
            put("targeted", policy.targetedCrawl)
            put("automatic", policy.autoExamination.name)
            put("ordinary", policy.ordinaryChatAutoExamination)
            put("background", policy.backgroundExamination)
            put("remote", features.remoteMcpConnections)
            put("selection_scope", Json.encodeToJsonElement(if (chatId == null) features.conversationResearch.toSortedMap() else features.conversationResearch.filterKeys { it == chatId }))
            put(
                "profiles",
                JsonArray(
                    relevant.map { profile ->
                        buildJsonObject {
                            put("uid", profile.uid)
                            put("revision", ConfiguredDispatchAuthority.profileRevision(profile))
                            put("paper_plugin", features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.DEEP_RESEARCH))
                            val delegation = features.delegationFor(profile.uid)
                            put("research", Json.encodeToJsonElement(delegation.deepResearch))
                            put("reviewer", delegation.reviewerProfileUid)
                            put("reviewer_enabled", delegation.reviewerEnabled)
                            put("review_mode", delegation.reviewMode.name)
                        }
                    }
                )
            )
            put("token_budget", Json.encodeToJsonElement(features.tokenBudget))
            val priceKeys = relevant.flatMap { listOf(it.uid, it.model) }.toSet()
            put("spend", Json.encodeToJsonElement(features.spendBudget.copy(prices = features.spendBudget.prices.filterKeys { it in priceKeys }.mapValues { (_, price) -> price.copy(checkedAt = 0, source = "") })))
        }.toString()
    )
}
