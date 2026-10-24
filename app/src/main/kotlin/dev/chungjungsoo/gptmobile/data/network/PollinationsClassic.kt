package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider

/** Reviewed Classic route: structured POST, with a fixed upstream generation ceiling.
 * https://github.com/pollinations/pollinations/blob/master/text.pollinations.ai/cloudflare-cache/src/origin.js
 * Its MAX_TOKENS is forwarded unconditionally, including when a client asks for less.
 */
internal object PollinationsClassic {
    const val SERIALIZER_REVISION = "pollinations-classic-post-v1"

    fun requireText(request: ChatCompletionRequest) {
        require(request.messages.all { message -> message.content.orEmpty().all { it is TextContent } }) {
            "Pollinations Classic supports text only. Choose another profile for attachments."
        }
    }

    fun requireOutputAllowance(tokens: Long) {
        val fixed = requireNotNull(FreeAiProvider.POLLINATIONS.fixedOutputTokens)
        if (tokens < fixed) {
            throw FreeProviderException(
                FreeFailureCategory.REQUEST_SCHEMA_INCOMPATIBLE,
                "Pollinations Classic enforces a fixed $fixed-token generation ceiling and cannot honor the smaller $tokens-token allowance. Increase the output allowance or choose another provider. No request was sent."
            )
        }
    }
}
