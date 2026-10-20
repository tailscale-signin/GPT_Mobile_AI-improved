package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformJobHintClassificationTest {
    @Test fun ignoredPlatformSchedulingHintDoesNotCountAsAnAppFailure() {
        assertFalse(classifyDiagnostic("W", "Android", "W/JobInfo: Requested important-while-foreground flag for job116 is ignored and takes no effect").actionable)
        assertTrue(classifyDiagnostic("E", "Android", "E/JobInfo: Requested important-while-foreground flag for job116 is ignored and takes no effect").actionable)
        assertTrue(classifyDiagnostic("W", "Android", "W/JobInfo: Unable to schedule the requested job").actionable)
    }
}
