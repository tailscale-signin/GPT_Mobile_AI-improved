package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.dto.google.common.InlineData
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse

private val imageProfiles = setOf(
    "gemini-2.5-flash-image",
    "gemini-3.1-flash-image",
    "gemini-3.1-flash-image-preview",
    "gemini-3-pro-image",
    "gemini-3-pro-image-preview",
    "gemini-nano-banana-2.1"
)

internal fun geminiResponseModalities(model: String): List<String>? =
    if (model.removePrefix("models/") in imageProfiles) listOf("TEXT", "IMAGE") else null

/** Keep visible binary parts within this attempt until the provider finishes the candidate. */
internal class GeminiImageOutput {
    private val pending = mutableListOf<InlineData>()
    private var omitted = false
    private var finished = false

    fun discard() {
        pending.clear()
        finished = true
    }

    fun accept(response: GenerateContentResponse, save: (InlineData) -> String?): String? {
        if (finished) return null
        if (response.error != null || response.promptFeedback?.blockReason != null) {
            discard()
            return null
        }
        val candidate = response.candidates.orEmpty().firstOrNull { it.index == 0 } ?: return null
        candidate.content?.parts.orEmpty().forEach { part ->
            val image = part.inlineData
            if (part.thought != true && image != null && image.mimeType.startsWith("image/")) {
                if (image.mimeType !in setOf("image/png", "image/jpeg", "image/webp") || image.data.length !in 1..5_592_408) {
                    omitted = true
                } else if (image !in pending) {
                    if (pending.size < 2) pending += image else omitted = true
                }
            }
        }
        val reason = candidate.finishReason ?: return null
        finished = true
        if (reason != "STOP") {
            pending.clear()
            return null
        }
        val lines = pending.mapIndexed { index, image ->
            val uri = save(image)
            if (uri != null) "[Open image ${index + 1}]($uri)" else "An image was returned but its preview could not be saved."
        }.toMutableList()
        pending.clear()
        if (omitted) lines += "Some returned images exceeded this preview’s format or size limits."
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "\n\n", postfix = "\n\n")
    }
}
