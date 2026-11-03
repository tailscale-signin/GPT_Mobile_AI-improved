package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSearchActivityTest {
    private fun event(name: String = "web_search", result: String? = null, status: String = ToolEventStatus.COMPLETED) = ToolEvent(
        eventId = "event", runId = "run", sequence = 1, callId = "call", connectionUidSnapshot = null, connectionNameSnapshot = null,
        toolName = name, modelToolName = name, arguments = "{\"limit\":22}", result = result, resultType = "JSON", status = status
    )

    @Test fun countsObservedDomainsRatherThanRequestedLimit() {
        val search = requireNotNull(gitHubSearchActivity(listOf(event(result = """{"results":[{"url":"https://github.com/a"},{"url":"https://github.com/b"},{"url":"https://developer.android.com/a"}]}"""))))
        assertEquals(2, search.websites)
        assertEquals("Found 2 websites", search.label)
    }

    @Test fun pendingSearchNeverInventsCount() {
        val search = requireNotNull(gitHubSearchActivity(listOf(event(status = ToolEventStatus.RUNNING))))
        assertEquals("Searching websites", search.label)
    }

    @Test fun completedEmptySearchDoesNotClaimItIsStillRunning() {
        val search = requireNotNull(gitHubSearchActivity(listOf(event(result = "{\"results\":[]}"))))
        assertEquals("Search complete · no websites found", search.label)
    }

    @Test fun githubCodeSearchAndFailedRequestsDoNotBecomeWebsitePreviews() {
        assertNull(gitHubSearchActivity(listOf(event(name = "github_search"))))
        assertNull(gitHubSearchActivity(listOf(event(status = ToolEventStatus.FAILED))))
    }
}
