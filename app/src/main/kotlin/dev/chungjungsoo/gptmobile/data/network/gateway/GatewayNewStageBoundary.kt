package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage

/** Move the actual new user turn behind completed history; never fabricate, duplicate or retype evidence. */
internal fun gatewayNewStageBoundary(conversation: List<ChatMessage>, retainedHistory: List<ChatMessage>): List<ChatMessage> {
    // Identify the actual user frame before tool-media USER frames can be appended.
    val currentUser = conversation.indexOfLast { it.role == Role.USER }
    check(currentUser >= 0 && !conversation[currentUser].content.isNullOrEmpty()) {
        "GATEWAY_NEW_STAGE_BOUNDARY_REQUIRED: The actual continuation request is unavailable. Restore the conversation before starting unfinished work; no request was dispatched."
    }
    if (retainedHistory.any { it.role == Role.TOOL || !it.toolCalls.isNullOrEmpty() }) {
        check(conversation.take(currentUser).any { it.role == Role.USER }) {
            "GATEWAY_NEW_STAGE_BOUNDARY_REQUIRED: The original task and new continuation request must both remain in context. Send Continue with the saved conversation; no request was dispatched."
        }
    }
    return conversation.filterIndexed { index, _ -> index != currentUser } + retainedHistory + conversation[currentUser]
}
