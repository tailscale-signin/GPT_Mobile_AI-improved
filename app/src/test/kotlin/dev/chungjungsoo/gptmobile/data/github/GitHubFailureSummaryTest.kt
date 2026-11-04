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

    @Test fun missingCallIdsPreserveIndependentFailuresAndDeduplicateReceiptIds() {
        fun failure(receiptId: String? = null) = buildJsonObject {
            put("trusted", true)
            put("success", false)
            put("call_id", "")
            receiptId?.let { put("receipt_id", it) }
            put("error_code", "INVALID_ARGUMENTS")
        }
        val report = GitHubFailureSummary.render(mapOf("one" to failure(), "two" to failure(), "three" to failure("same"), "four" to failure("same")))
        assertTrue(report.contains("INVALID_ARGUMENTS | 3"))
    }

    @Test fun observedPreflightRejectionIsIncludedWithoutClaimingDispatch() {
        val report = GitHubFailureSummary.render(
            mapOf(
                "blocked" to buildJsonObject {
                    put("success", false)
                    put("dispatched", false)
                    put("error_code", "BUDGET_EXHAUSTED")
                }
            )
        )
        assertTrue(report.contains("BUDGET_EXHAUSTED | 1"))
    }
}
