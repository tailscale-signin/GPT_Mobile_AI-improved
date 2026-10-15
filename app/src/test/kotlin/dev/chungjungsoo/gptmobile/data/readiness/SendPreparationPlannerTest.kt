package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

class SendPreparationPlannerTest {
    @Test fun wholeGreetingHasNoOptionalWork() {
        listOf("hi", "  Hello!  ", "\u2003Good morning…", "hey?").forEach { text ->
            val plan = SendPreparationPlanner.classify(text.replace("…", "."))
            assertEquals(text, PreparationKind.DIRECT, plan.kind)
            assertEquals(0L, plan.optionalBudgetMillis)
        }
    }

    @Test fun continuationQuotedAndMixedMessagesAreConservative() {
        listOf("yes", "do it", "continue", "retry", "\"hi\"", "Hi, search this", "bonjour", "hi\nremember this").forEach {
            assertEquals(it, PreparationKind.CONTEXTUAL, SendPreparationPlanner.classify(it).kind)
        }
        assertEquals(PreparationKind.DIRECT, SendPreparationPlanner.classify("hi", hasHistory = true).kind)
    }

    @Test fun requiredWorkCannotBeBypassedByGreeting() {
        assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify("hi", hasAttachments = true).kind)
        assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify("hi", requiredWorkflow = true).kind)
        assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify("hi", recovery = true).kind)
        assertEquals(PreparationKind.REQUIRED_WORK, SendPreparationPlanner.classify("hi", synthesis = true).kind)
    }

    @Test fun contextualStagesShareOneDeadline() {
        val plan = SendPreparationPlanner.classify("Explain this algorithm")
        assertEquals(250L, plan.remainingMillis(1_000_000, 1_000_000))
        assertEquals(50L, plan.remainingMillis(1_000_000, 201_000_000))
        assertEquals(0L, plan.remainingMillis(1_000_000, 401_000_000))
    }
}
