package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegateOutcomeTest {
    @Test fun `an output limit can contain reasoning only or usable partial content`() {
        assertEquals(DelegateContentState.REASONING_ONLY, delegateContentState(false, 8868, true))
        assertEquals(DelegateContentState.PARTIAL_OUTPUT, delegateContentState(true, 8868, true))
        assertEquals(DelegateContentState.EMPTY_OUTPUT, delegateContentState(false, 0, false))
    }

    @Test fun `review availability and timeout never become evidence rejection`() {
        assertEquals(DelegateReviewState.UNAVAILABLE, delegateReviewState("[REVIEW_UNAVAILABLE][REVIEWER_UNAVAILABLE · unverified] saved facts"))
        assertEquals(DelegateReviewState.TIMED_OUT, delegateReviewState("[REVIEW_TIMEOUT][UNVERIFIED] saved facts"))
        assertEquals(DelegateReviewState.REJECTED, delegateReviewState("[REVIEW_REJECTED] unsupported claim"))
        assertEquals(DelegateReviewState.PASSED, delegateReviewState("[Reviewer Score: 90/100] supported facts"))
    }
}
