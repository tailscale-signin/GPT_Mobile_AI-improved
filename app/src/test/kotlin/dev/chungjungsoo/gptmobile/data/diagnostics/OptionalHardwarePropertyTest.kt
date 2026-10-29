package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalHardwarePropertyTest {
    @Test fun inaccessibleOptionalSocLabelDoesNotCountAsModelFailure() {
        val classification = classifyDiagnostic("W", "Android", "W/libc (15901): Access denied finding property \"ro.hardware.chipname\"")
        assertEquals("platform_optional_property", classification.category)
        assertFalse(classification.actionable)
        assertTrue(classifyDiagnostic("W", "Android", "W/libc (15901): Access denied finding property \"unknown.property\"").actionable)
        assertTrue(classifyDiagnostic("E", "LocalModel", "Model load failed").actionable)
        assertTrue(classifyDiagnostic("E", "Android", "E/System (15901): Uncaught exception thrown by finalizer").actionable)
    }
}
