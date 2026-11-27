package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySessionJobIdentityTest {
    @Test fun unverifiedMetadataAndProgressCannotCreateOrReplaceASavedJob() {
        for (saved in listOf(null, "restored-job")) {
            val identity = GatewaySessionJobIdentity(saved)
            identity.observe(ChatCompletionChunk(gatewayMetadata = GatewayResponseMetadata(jobId = "unverified-body")))
            identity.observe(ChatCompletionChunk(gatewayProgress = GatewayProgress(jobId = "unverified-progress")))
            assertEquals(saved, identity.jobId)
            identity.observe(ChatCompletionChunk(gatewayMetadata = GatewayResponseMetadata(jobId = "verified-job", transportVerified = true), gatewayProgress = GatewayProgress(jobId = "another-progress")))
            assertEquals("verified-job", identity.jobId)
            identity.observe(ChatCompletionChunk(gatewayMetadata = GatewayResponseMetadata(jobId = "later-body"), gatewayProgress = GatewayProgress(jobId = "later-progress")))
            identity.observe(ChatCompletionChunk(gatewayMetadata = GatewayResponseMetadata(transportVerified = true)))
            assertEquals("verified-job", identity.jobId)
        }
    }

    @Test fun decodedJsonCannotGrantTheTransportVerificationFlag() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<GatewayResponseMetadata>("""{"jobId":"body-job","transportVerified":true}""")
        val identity = GatewaySessionJobIdentity()
        identity.observe(ChatCompletionChunk(gatewayMetadata = decoded))
        assertNull(identity.jobId)
    }
}
