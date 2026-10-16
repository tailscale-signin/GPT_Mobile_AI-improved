package dev.chungjungsoo.gptmobile.data.nvidia

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Only apply parameters documented for a specific NVIDIA-hosted model family.
 * Never apply these transforms to arbitrary OpenAI-compatible servers or unknown NIMs.
 */
object NvidiaRequestPolicy {
    fun prepare(request: ChatCompletionRequest, reasoningRequested: Boolean, reasoningAllowed: Boolean): ChatCompletionRequest {
        val enabled = reasoningRequested && reasoningAllowed
        return when (NvidiaModelCapabilities.forModel(request.model).reasoningControl) {
            NvidiaReasoningControl.EFFORT -> request.copy(
                // GPT-OSS cannot disable reasoning completely. 'low' is its lowest supported effort.
                reasoningEffort = if (enabled) "medium" else "low",
                topK = null
            )
            NvidiaReasoningControl.TEMPLATE -> request.copy(
                chatTemplateKwargs = JsonObject(
                    request.chatTemplateKwargs.orEmpty() + ("enable_thinking" to JsonPrimitive(enabled))
                )
            )
            NvidiaReasoningControl.UNKNOWN -> request
        }
    }

    /** Describe *transmitted* control, not unobservable server-side reasoning. */
    fun effectiveControl(modelId: String, reasoningRequested: Boolean, reasoningAllowed: Boolean): String? =
        when (NvidiaModelCapabilities.forModel(modelId).reasoningControl) {
            NvidiaReasoningControl.EFFORT -> if (reasoningRequested && reasoningAllowed) "medium" else "low (mandatory reasoning)"
            NvidiaReasoningControl.TEMPLATE -> if (reasoningRequested && reasoningAllowed) "enable_thinking=true" else "enable_thinking=false"
            NvidiaReasoningControl.UNKNOWN -> null
        }
}
