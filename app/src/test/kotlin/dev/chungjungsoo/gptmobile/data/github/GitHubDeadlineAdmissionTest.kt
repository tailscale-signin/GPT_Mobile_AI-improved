package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubDeadlineAdmissionTest {
    @Test fun sameContractTransitionsFromOpenToExpiredAtItsExactDeadline() {
        val contract = buildJsonObject {
            put("accepted_at_ms", 1000)
            put("deadline_at_ms", 61_000)
        }
        val open = GitHubExecutionLimits.admission(contract, 60_999)
        assertEquals(1L, open.remainingMillis)
        assertNull(open.failure())
        val expired = GitHubExecutionLimits.admission(contract, 61_000)
        assertEquals(0L, expired.remainingMillis)
        val failure = requireNotNull(expired.failure())
        assertEquals(JsonPrimitive("ADMISSION"), failure.failureDetails?.get("task_deadline_check"))
        assertEquals(JsonPrimitive("AUTHORIZE_NEW_TIME_WINDOW"), failure.failureDetails?.get("recovery_action"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("resume_allowed"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("resume_requires_time_grant"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("remaining_tool_calls_preserved"))
    }

    @Test fun unavailableOrInvalidAcceptedClockNeverBecomesAnUnboundedWindow() {
        val malformed = buildJsonObject {
            put("accepted_at_ms", 1000)
            put("deadline_at_ms", "61000")
        }
        for (contract in listOf(null, malformed)) {
            val blocked = GitHubExecutionLimits.admission(contract, 2000)
            assertEquals(0L, blocked.remainingMillis)
            assertEquals("GITHUB_TASK_IDENTITY_CHANGED", blocked.failure()?.errorCode)
        }
        val unbounded = GitHubExecutionLimits.admission(buildJsonObject { put("accepted_at_ms", 1000) }, 2000)
        assertNull(unbounded.remainingMillis)
        assertNull(unbounded.failure())
    }
}
