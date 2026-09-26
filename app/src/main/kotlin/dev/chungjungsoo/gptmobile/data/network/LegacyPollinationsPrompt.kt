package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest

/** The anonymous legacy GET API returns plain text, not OpenAI SSE. */
internal fun legacyPollinationsPrompt(request: ChatCompletionRequest): String {
    require(request.messages.all { message -> message.content.orEmpty().all { it is TextContent } }) {
        "Pollinations legacy supports text only. Choose another profile for attachments."
    }
    val prompt = request.messages.joinToString("\n\n") { message ->
        "${message.role.name.lowercase()}: ${message.content.orEmpty().filterIsInstance<TextContent>().joinToString("\n") { it.text }}"
    } + "\n\nassistant:"
    require(prompt.length <= 6000) { "This conversation is too long for Pollinations legacy. Start a new chat or choose another Free provider." }
    return prompt
}
