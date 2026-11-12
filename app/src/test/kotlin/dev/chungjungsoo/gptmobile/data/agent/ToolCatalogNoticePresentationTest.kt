package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogNoticePresentationTest {
    private fun localOnly(tool: String) = "$tool: PERSONAL_DATA_LOCAL_ONLY: This tool cannot receive the local-only context in this request."
    private fun permission(tool: String) = "$tool: PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED: This conversation contains personal context."

    @Test fun repeatedPrivacyCatalogDenialsBecomeOneAccuratelyCountedNotice() {
        val notices = listOf("read_url", "web_search", "web_search_first", "web_search_second", "web_search_third").map(::localOnly)
        val grouped = ToolCatalogNoticePresentation.notices(notices + notices.first())
        assertEquals(1, grouped.size)
        assertTrue(grouped.single().startsWith("5 external tool entries are unavailable"))
        assertTrue(grouped.single().contains("PERSONAL_DATA_LOCAL_ONLY"))
        assertTrue(grouped.single().contains("Other authorized repository tools may remain available"))
        assertFalse(grouped.single().contains("5 services"))
        assertFalse(grouped.single().contains("web_search_first"))
    }

    @Test fun otherErrorsAndSingleDenialsRemainUnchangedInOrder() {
        val authentication = "github_read: AUTH_REQUIRED: Restore the selected connection."
        val attemptedFailure = "PERSONAL_DATA_LOCAL_ONLY: This attempted operation was rejected before dispatch."
        val discoveryFailure = "search: MCP_CONNECTION_FAILED: Connection unavailable."
        val singlePermission = permission("maps")
        val input = listOf(authentication, localOnly("read_url"), attemptedFailure, localOnly("web_search"), discoveryFailure, singlePermission)
        val result = ToolCatalogNoticePresentation.notices(input)
        assertEquals(listOf(authentication, attemptedFailure, discoveryFailure, singlePermission), result.filterNot { it.startsWith("2 external tool entries") })
        assertEquals(authentication, result.first())
        assertTrue(result[1].startsWith("2 external tool entries"))
        assertEquals(listOf(localOnly("read_url")), ToolCatalogNoticePresentation.notices(listOf(localOnly("read_url"))))
    }

    @Test fun differentPrivacyReasonsKeepTheirOwnRecoveryGuidance() {
        val result = ToolCatalogNoticePresentation.notices(listOf(localOnly("read_url"), permission("maps"), localOnly("web_search"), permission("lookup")))
        assertEquals(2, result.size)
        assertTrue(result[0].contains("PERSONAL_DATA_LOCAL_ONLY"))
        assertTrue(result[0].contains("request without that local-only context"))
        assertTrue(result[1].contains("PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED"))
        assertTrue(result[1].contains("sharing permission is missing"))
        assertTrue(result.all { it.startsWith("2 external tool entries") })
    }
}
