package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineTaskPolicyTest {
    @Test fun narrowInlineTransformationsAndGreetingsCanUseLessAutomaticPreparation() {
        for (task in listOf("Hello!", "Thank you.", "Good morning")) assertEquals(task, RoutineTaskKind.GREETING, RoutineTaskPolicy.classify(task))
        for (task in listOf("Rewrite: Thank you for your help.", "Please reword this: The meeting is tomorrow.", "Fix the grammar: She go to school.", "Translate into French: Hello.")) {
            assertEquals(task, RoutineTaskKind.INLINE_TEXT_TRANSFORMATION, RoutineTaskPolicy.classify(task))
        }
    }

    @Test fun unknownComplexCurrentSensitiveAndExternalWorkNeverUsesTheRoutineShortcut() {
        for (task in listOf("Who is the president?", "Explain quantum mechanics", "Hello, what is the latest news?", "Rewrite: Is this medication safe?", "Rewrite: This is legal advice.", "Rewrite the attached document: please", "Rewrite: https://example.org/text", "Rewrite: Hello. Then send it to my colleague.", "Verify the grammar: It looks fine.", "Rewrite: " + "words ".repeat(60))) {
            assertNull(task, RoutineTaskPolicy.classify(task))
        }
    }

    @Test fun adaptiveRoutineParticipationKeepsThePrimaryAndOneOtherPerspective() {
        val selected = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d"), CombinedRequestScope.participants("Rewrite: Thank you.", selected, "d", adaptive = true, research = false))
        assertEquals(listOf("a", "b"), CombinedRequestScope.participants("Hello!", selected, "not-selected", adaptive = true, research = false))
        assertEquals(listOf("a"), CombinedRequestScope.participants("Hello!", listOf("a"), "a", adaptive = true, research = false))
    }

    @Test fun explicitParticipantsResearchAndNonAdaptiveSelectionRemainAuthoritative() {
        val selected = listOf("a", "b", "c", "d")
        for (task in listOf("Ask Claude and Gemini to reword this: Hello.", "Compare all of the enabled AI profiles", "Have every model respond", "Use the selected models", "Ask both assistants", "Have 4 contributors respond")) {
            assertEquals(task, selected, CombinedRequestScope.participants(task, selected, "d", adaptive = true, research = false))
        }
        assertEquals(selected, CombinedRequestScope.participants("Hello!", selected, "d", adaptive = false, research = false))
        assertEquals(selected, CombinedRequestScope.participants("Hello!", selected, "d", adaptive = true, research = true))
    }
}
