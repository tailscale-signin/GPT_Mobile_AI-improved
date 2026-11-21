package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeExitEvidenceTest {
    @Test fun unmatchedSignalNineIsRetainedWithoutClaimingOutOfMemory() {
        assertEquals("SIGKILL_UNATTRIBUTED", nativeSignalExitCategory(9, null))
        assertEquals("APP_REQUESTED_RELEASE", nativeSignalExitCategory(9, "APP_REQUESTED_RELEASE"))
        assertEquals("SIGNALLED_UNATTRIBUTED", nativeSignalExitCategory(11, null))
    }
}
