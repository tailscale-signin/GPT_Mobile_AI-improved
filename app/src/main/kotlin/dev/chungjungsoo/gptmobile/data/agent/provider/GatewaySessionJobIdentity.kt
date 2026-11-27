package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk

/** A provider body or progress frame cannot replace the authenticated job used by the next POST. */
internal class GatewaySessionJobIdentity(verifiedRestoredJobId: String? = null) {
    var jobId: String? = verifiedRestoredJobId
        private set

    fun observe(chunk: ChatCompletionChunk) {
        val metadata = chunk.gatewayMetadata
        if (metadata?.transportVerified == true && metadata.jobId != null) jobId = metadata.jobId
    }
}
