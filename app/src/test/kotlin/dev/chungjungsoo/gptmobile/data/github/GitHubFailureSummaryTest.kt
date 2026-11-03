package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFailureSummaryTest {
    @Test fun reportPreservesObservedFailuresWithoutClaimingARootCauseOrExposingRawPayloads() {
        val receipt = buildJsonObject {
            put("trusted", true)
            put("success", false)
            put("call_id", "one")
            put("error_code", "SAVED_VIEW_UNAVAILABLE")
            put("result", "private source")
        }
        val report = GitHubFailureSummary.render(
            mapOf(
                "one" to receipt,
                "duplicate" to receipt,
                "untrusted" to buildJsonObject {
                    put("success", false)
                    put("error_code", "OTHER")
                }
            )
        )
        assertTrue(report.contains("SAVED_VIEW_UNAVAILABLE | 1"))
        assertTrue(report.contains("do not establish the cause"))
        assertFalse(report.contains("private source"))
        assertFalse(report.contains("OTHER"))
    }
}
