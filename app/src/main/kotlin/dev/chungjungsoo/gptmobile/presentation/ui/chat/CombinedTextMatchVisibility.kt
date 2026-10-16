package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings

/**
 * Verbose text-match evidence belongs to the Debug experience, not the normal Combined answer.
 * Keep this independent from CombinedWordingPresentation, which colours normal answer prose.
 */
internal fun shouldShowCombinedTextMatchDiagnostics(
    isCombinedConversation: Boolean,
    debugMode: Boolean,
    settings: AppFeatureSettings
): Boolean = isCombinedConversation && debugMode && settings.debugShowCombinedTextMatches
