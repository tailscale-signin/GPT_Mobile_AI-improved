package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugActivityContentTest {
    @Test fun researchPlanShowsQueriesWithoutJsonSyntax() {
        val result = debugActivityContent("""{"queries":["Randy Savage death","Randy Savage died"],"urls":[]}""")
        assertEquals("Research plan", result.title)
        assertTrue(result.body.contains("2 search queries"))
        assertTrue(result.body.contains("• Randy Savage death"))
        assertFalse(result.body.contains("{"))
    }

    @Test fun incompleteReviewerFragmentsStayHidden() {
        for (fragment in listOf("{\"review_score\":100,", ",\"verdict\":\"PASS\",\"issues\"")) {
            val result = debugActivityContent(fragment, reviewer = true)
            assertEquals("Preparing the review…", result.body)
        }
    }

    @Test fun reviewDisplaysTheActualScoreAndFindings() {
        val result = debugActivityContent("""{"review_score":65,"verdict":"REJECT","issues":["Missing source"],"corrections":null}""", reviewer = true)
        assertEquals(65, result.score)
        assertEquals("REJECT", result.verdict)
        assertTrue(result.body.contains("Missing source"))
        assertFalse(result.body.contains("null"))
    }

    @Test fun delegateAnswerKeepsSourceMarkers() {
        assertEquals("He died on May 20, 2011. [S2]", debugActivityContent("He died on May 20, 2011. [S2]").body)
    }

    @Test fun concatenatedPlanAndAnswerRetainProseAndHideReviewerFragments() {
        val raw = """{"queries":["Randy Savage death"],"urls":[]}

Delegation
He died on May 20, 2011. [S2]
Reviewer
,"verdict":"PASS","issues"
"""
        val body = debugActivityContent(raw).body
        assertTrue(body.contains("• Randy Savage death"))
        assertTrue(body.contains("He died on May 20, 2011. [S2]"))
        assertFalse(body.contains("{\"queries\""))
        assertFalse(body.contains(",\"verdict\""))
    }
}
