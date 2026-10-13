package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchTimeoutsTest {
    @Test fun aggregateDeadlineAllowsPhysicalRequestAndRetryCompletion() {
        assertTrue(ResearchTimeouts.operationMillis("deep_research_search_papers", 15000) > ResearchTimeouts.HTTP_ATTEMPT_MS * ResearchTimeouts.HTTP_ATTEMPTS)
        assertTrue(ResearchTimeouts.operationMillis("deep_research_read_paper", 15000) > ResearchTimeouts.HTTP_ATTEMPT_MS)
        assertEquals(15000L, ResearchTimeouts.operationMillis("read_url", 15000))
        assertEquals(500000L, ResearchTimeouts.operationMillis("deep_research_search_papers", 500000))
    }

    @Test fun readRetryPolicyCannotAuthorizeMutationReplay() {
        assertTrue(ResearchTimeouts.isAcademicRead("deep_research_read_paper"))
        for (tool in listOf("delete_file", "github_commit", "deep_research_delete", "unknown")) assertFalse(ResearchTimeouts.isAcademicRead(tool))
    }
}
