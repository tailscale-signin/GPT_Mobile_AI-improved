package dev.chungjungsoo.gptmobile.presentation.ui.home

internal fun movePinnedConversation(order: List<Int>, chatId: Int, targetIndex: Int): List<Int> =
    order.filterNot { it == chatId }.toMutableList().apply { add(targetIndex.coerceIn(0, size), chatId) }

internal fun conversationPinDrop(
    isPinned: Boolean,
    dropY: Float,
    windowHeight: Int,
    firstConversationTop: Float,
    pinTargetHeight: Float,
    pinnedCenters: List<Pair<Int, Float>>,
    verticalDrag: Float
): Int? = when {
    kotlin.math.abs(verticalDrag) < pinTargetHeight -> null
    isPinned && verticalDrag > 0f && dropY >= windowHeight * 0.5f -> -1
    verticalDrag < 0f && dropY <= firstConversationTop + pinTargetHeight -> pinnedCenters.count { it.second < dropY }
    isPinned && pinnedCenters.isNotEmpty() && dropY <= pinnedCenters.maxOf { it.second } + pinTargetHeight -> pinnedCenters.count { it.second < dropY }
    else -> null
}
