package dev.chungjungsoo.gptmobile.data.network.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTemplatesTest {
    @Test fun everyCategoryHasAUserFacingTemplate() {
        for (category in ErrorCategory.entries) {
            val error = ClassifiedError(category, ErrorSeverity.MEDIUM, false, 0, "Recovery guidance")
            val template = ErrorTemplates.getTemplateFor(error)
            assertTrue(template.title.isNotBlank())
            assertTrue(template.description.isNotBlank())
        }
    }

    @Test fun preparationFailuresPreserveGuidanceAndRequireExplicitRecovery() {
        val cases = mapOf(
            "INVALID_OUTPUT_CAP" to "Open Settings",
            "DISPATCH_POLICY_CHANGED" to "Retry",
            "MEMORY_STALE" to "Retry",
            "SOURCE_PERMISSION_REVOKED" to "Review Access"
        )
        for ((code, action) in cases) {
            val error = ErrorClassification.classify(IllegalStateException(code))
            val template = ErrorTemplates.getTemplateFor(error)
            assertEquals(error.userMessage, template.description)
            assertEquals(action, template.actionButtonLabel)
            assertFalse(template.autoRetry)
        }
    }
}
