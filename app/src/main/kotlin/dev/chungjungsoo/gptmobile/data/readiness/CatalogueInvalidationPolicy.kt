package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings

/** Inference/presentation edits invalidate their own preparation, not unrelated MCP schemas. */
internal object CatalogueInvalidationPolicy {
    fun profileChanged(previous: PlatformV2?, next: PlatformV2): Boolean = previous == null ||
        previous.uid != next.uid ||
        previous.enabled != next.enabled ||
        previous.compatibleType != next.compatibleType ||
        previous.apiUrl != next.apiUrl ||
        previous.token != next.token ||
        previous.secretRef != next.secretRef ||
        previous.providerConnectionUid != next.providerConnectionUid ||
        previous.disableAllTools != next.disableAllTools ||
        previous.disableRemoteTools != next.disableRemoteTools ||
        previous.disableLocalTools != next.disableLocalTools

    fun featuresChanged(previous: AppFeatureSettings, next: AppFeatureSettings): Boolean =
        previous.readiness.cacheGeneration != next.readiness.cacheGeneration ||
            previous.copy(
                lastChatProfileUids = next.lastChatProfileUids,
                readiness = next.readiness,
                newChatScores = next.newChatScores,
                newChatPrices = next.newChatPrices,
                newChatPriceRefresh = next.newChatPriceRefresh,
                newChatCommunity = next.newChatCommunity,
                newChatBackgroundPrices = next.newChatBackgroundPrices,
                manualPrices = next.manualPrices,
                compactChatList = next.compactChatList,
                smoothStreaming = next.smoothStreaming,
                centerUnread = next.centerUnread,
                responseAnimation = next.responseAnimation,
                edgeFades = next.edgeFades,
                messageTimestamps = next.messageTimestamps,
                responseNotifications = next.responseNotifications,
                automaticConversationTitles = next.automaticConversationTitles,
                archiveOlderAssistantReplies = next.archiveOlderAssistantReplies,
                smartSuggestions = next.smartSuggestions,
                showReasoning = next.showReasoning,
                diagnosticsCollection = next.diagnosticsCollection,
                profileAttributionColors = next.profileAttributionColors,
                debugShowToolCalls = next.debugShowToolCalls,
                debugShowTotalTokens = next.debugShowTotalTokens,
                debugShowTokenSpeed = next.debugShowTokenSpeed,
                debugShowTimeToFirstToken = next.debugShowTimeToFirstToken,
                debugShowRuntime = next.debugShowRuntime,
                debugShowHardware = next.debugShowHardware,
                debugShowNetwork = next.debugShowNetwork,
                debugShowDelegationTrace = next.debugShowDelegationTrace,
                debugShowReviewerTrace = next.debugShowReviewerTrace,
                debugShowMemoryRecall = next.debugShowMemoryRecall,
                debugShowTokenComparison = next.debugShowTokenComparison,
                debugShowCombinedTextMatches = next.debugShowCombinedTextMatches,
                conversationReasoning = next.conversationReasoning
            ) != next
}
