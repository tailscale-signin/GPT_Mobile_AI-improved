package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterPreflightTest {
    private val request = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p"), mapOf("p" to "model"), "Explain in 200 words", singlePass = true)
    private val writer = PlatformV2(uid = "p", name = "Writer", model = "model", compatibleType = ClientType.CUSTOM, apiUrl = "https://writer.example/v1", enabled = true, maxTokens = 16384)

    @Test fun missingCredentialsOutputAndContextFailBeforeCollection() {
        for (profile in listOf(writer.copy(compatibleType = ClientType.OPENAI, token = ""), writer.copy(enabled = false), writer.copy(maxTokens = 64))) {
            assertTrue(runCatching { CombinedWriterPreflight.validate(profile, request, TokenBudgetSettings()) }.isFailure)
        }
        assertTrue(runCatching { CombinedWriterPreflight.validate(writer, request, TokenBudgetSettings(contextTokens = 512)) }.isFailure)
        assertTrue(runCatching { CombinedWriterPreflight.validate(writer, request, TokenBudgetSettings(outputTokens = 64)) }.isFailure)
    }

    @Test fun anonymousCompatibleWriterCanPassLocalReadinessWithoutPretendingToVerifyTheServer() {
        CombinedWriterPreflight.validate(writer, request, TokenBudgetSettings(contextTokens = 32768))
    }
}
