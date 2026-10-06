package dev.chungjungsoo.gptmobile.presentation.ui.home

internal fun movePinnedConversation(order: List<Int>, chatId: Int, targetIndex: Int): List<Int> =
    order.filterNot { it == chatId }.toMutableList().apply { add(targetIndex.coerceIn(0, size), chatId) }

internal fun conversationPinDrop(
    isPinned: Boolean,
    dropY: Float,
    windowHeight: Int,
    firstConversationTop: Float,
    pinTargetHeight: Float,
    pinnedCenters: List<Pair<Int, Float>>
): Int? = when {
    isPinned && dropY >= windowHeight * 0.7f -> -1
    dropY <= firstConversationTop + pinTargetHeight -> pinnedCenters.count { it.second < dropY }
    isPinned && pinnedCenters.isNotEmpty() && dropY <= pinnedCenters.maxOf { it.second } + pinTargetHeight -> pinnedCenters.count { it.second < dropY }
    else -> null
}
