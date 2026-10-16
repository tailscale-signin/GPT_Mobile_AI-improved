package dev.chungjungsoo.gptmobile.data.assist

/** Optional preparation must never evict a conversation or override warm-only selection. */
internal fun canReplaceAssistEngine(preparation: AssistPreparation, busy: Boolean, conversationOpen: Boolean): Boolean =
    preparation == AssistPreparation.PREPARE_AUTOMATICALLY && !busy && !conversationOpen
