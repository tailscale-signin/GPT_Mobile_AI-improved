package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GitHubSearchCursorDiagnosticsTest {
    @Test fun observedBranchesAreDistinctAndIdentifiersArePrivate() {
        val missing = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.RETAINED_SEARCH_MISSING, "private-search", "12")
        val boundary = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.PAGE_BOUNDARY_UNOBSERVED, "private-search", "12")
        assertEquals(JsonPrimitive("RETAINED_SEARCH_MISSING"), missing["search_cursor_state"])
        assertEquals(JsonPrimitive("PAGE_BOUNDARY_UNOBSERVED"), boundary["search_cursor_state"])
        assertEquals(missing["search_id_hash"], boundary["search_id_hash"])
        assertEquals(missing["search_cursor_hash"], boundary["search_cursor_hash"])
        assertFalse(missing.toString().contains("private-search"))
        val domains = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.RETAINED_SEARCH_MISSING, "same", "same")
        assertNotEquals(domains["search_id_hash"], domains["search_cursor_hash"])
    }

    @Test fun unavailableOrOversizedIdentifiersAreOmitted() {
        val value = GitHubSearchCursorDiagnostics.observation(GitHubSearchCursorDiagnostics.State.RETAINED_SEARCH_MISSING, "x".repeat(257), "")
        assertEquals(setOf("search_cursor_state"), value.keys)
    }
}
