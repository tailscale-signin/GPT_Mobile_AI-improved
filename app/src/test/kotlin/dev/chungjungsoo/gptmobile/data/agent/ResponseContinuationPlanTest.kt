package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseContinuationPlanTest {
    private fun current(request: String) = ConversationTurn(MessageV2(id = 1, chatId = 1, platformType = null, content = request), null, isCurrentTurn = true)

    @Test fun automaticOutputUsesTheAdmittedContextReserveInsteadOfTheLargerRouteCeiling() {
        val settings = TokenBudgetSettings(contextTokens = 1024)
        val output = responseOutputAllowance(settings, routeOutputCap = 1024)
        assertEquals(256, output)
        val initial = ContextBudgetService.plan(listOf(current("Write 4096 words.")), "Preserve facts.", emptyList(), settings.copy(outputTokens = output))
        assertEquals(256, initial.outputTokens)
        val continuation = planResponseContinuation(initial.turns, initial.system, settings, "Saved paragraph. ".repeat(2000), "\nContinue the answer.", emptyList())
        assertEquals(256, continuation.outputTokens)
        assertTrue(continuation.promptTokens + requireNotNull(continuation.outputTokens) + 64 <= settings.contextTokens)
        assertEquals(128, responseOutputAllowance(settings, routeOutputCap = 128))
        assertEquals(96, responseOutputAllowance(settings.copy(outputTokens = 96), routeOutputCap = 1024))
        assertEquals(1024, responseOutputAllowance(TokenBudgetSettings(), routeOutputCap = 1024))
    }

    @Test fun fourThousandWordSavedAnswerFitsAOneThousandTokenContinuationWindow() {
        val request = "Explain Portuguese history in 4096 words with chronological headings."
        val draft = (1..4096).joinToString(" ") { "saved$it" }
        val settings = TokenBudgetSettings(contextTokens = 1024, outputTokens = 256)
        val plan = planResponseContinuation(listOf(current(request)), "Write accurately.", settings, draft, "\nContinue the remaining eras without repeating text.", emptyList())
        val sent = plan.turns.single().userMessage.content
        assertTrue(sent.startsWith(request))
        assertTrue(sent.contains("saved4096"))
        assertFalse(sent.contains("saved1 saved2"))
        assertTrue(ContextBudgetService.breakdown(plan.turns, plan.system, emptyList()).total + 256 + 64 <= 1024)
        assertEquals(4096, LongResponsePolicy.countWords(draft))
    }

    @Test fun originalUserScopeIsNeverClippedToMakeAContinuationFit() {
        val request = "Required original scope ".repeat(1000)
        val error = runCatching {
            planResponseContinuation(listOf(current(request)), "System", TokenBudgetSettings(contextTokens = 1024, outputTokens = 256), "Saved partial", "Continue", emptyList())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("CONTINUATION_CONTEXT_CAPACITY"))
    }

    @Test fun singleFinalWriterDoesNotDisableContributorContinuation() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(continueLongAnswers = false), listOf("p"), mapOf("p" to "model"), "History in 5000 words", acceptedAtMillis = 0, singlePass = true)
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root", CombinedRunRole.CONTRIBUTOR, "p")
        assertTrue(responseContinuationLimit(5000, 1024, context, false, false, "") >= 10)
        assertEquals(0, responseContinuationLimit(5000, 1024, context.forRole(CombinedRunRole.SYNTHESIS), true, false, ""))
        assertEquals(Int.MAX_VALUE, responseContinuationLimit(5000, 1024, context.forRole(CombinedRunRole.SYNTHESIS), true, true, ""))
    }

    @Test fun ordinaryAndMultiChatShareTheSameContinuationAllowance() {
        assertTrue(responseContinuationLimit(20000, 256, null, false, false, "") > 16)
    }

    @Test fun newConversationAllowsLongAnswersWhileArchivedStrictPoliciesRemainStrict() {
        val current = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("p"), mapOf("p" to "model"), "History in 5000 words", acceptedAtMillis = 0, singlePass = true)
        assertTrue(current.appendContinuations)
        val archived = kotlinx.serialization.json.Json.decodeFromString<CombinedRequestedPolicy>("""{"participantUids":["p"],"requestedModels":{"p":"model"},"userPrompt":"History","singlePass":true}""")
        assertFalse(archived.appendContinuations)
        assertFalse(archived.settings.continueLongAnswers)
    }
}
