package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayIndexSyncObservationTest {
    @Test fun realOperationWrapperRetainsTypedFailureAndUnknownIndexSafety() {
        fun wrapped(cause: Exception, unknown: Boolean = false) = GatewayGraphOperationException(cause, "private profile", "private host", "private repair", unknown)
        val http = GatewayIndexSyncObservation.deferred(failure = wrapped(GatewayGraphHttpFailure(503)))
        assertEquals("GRAPH_HTTP_REJECTED", http["reason"])
        assertEquals(503, http["http_status"])
        val admission = GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "private message", "ENABLE_ENGINEERING_MEMORY")
        assertEquals("ENABLE_ENGINEERING_MEMORY", GatewayIndexSyncObservation.deferred(failure = wrapped(admission))["repair_action"])
        val uncertain = GatewayIndexSyncObservation.deferred(failure = wrapped(admission, unknown = true))
        assertEquals("CHECK_SAVED_INDEX", uncertain["repair_action"])
        assertFalse(uncertain.toString().contains("private"))
    }

    @Test fun admissionFailureRetainsItsSpecificRepairWithoutLeakingExceptionText() {
        val observed = GatewayIndexSyncObservation.deferred(
            failure = GatewayGraphAdmissionException("GRAPH_CONFIGURATION_REQUIRED", "private host and repository path", "ENABLE_ENGINEERING_MEMORY")
        )
        assertEquals("GRAPH_CONFIGURATION_REQUIRED", observed["reason"])
        assertEquals("ENABLE_ENGINEERING_MEMORY", observed["repair_action"])
        assertEquals("unchanged", observed["previous_snapshot"])
        assertEquals(false, observed["sync_complete"])
        assertFalse(observed.toString().contains("private host"))
        val permission = GatewayIndexSyncObservation.deferred(failure = GatewayGraphAdmissionException("PERMISSION_DENIED", "private tools"))
        assertEquals("REVIEW_SELECTED_GITHUB_TOOL_PERMISSIONS", permission["repair_action"])
    }

    @Test fun unrecognizedAdmissionValuesCannotEscapeThroughStructuredDiagnostics() {
        val observed = GatewayIndexSyncObservation.deferred(failure = GatewayGraphAdmissionException("private-code", "private message", "private-action"))
        assertEquals("GRAPH_ADMISSION_REJECTED", observed["reason"])
        assertFalse(observed.toString().contains("private"))
    }

    @Test fun readinessAndHttpFailuresRetainOnlyBoundedDiagnosticFacts() {
        val unavailable = GatewayIndexSyncObservation.deferred(
            failure = GatewayGraphUnavailableException("private gateway", listOf(GatewayGraphPrerequisite.HOST_GITHUB_CONNECTION_MISSING))
        )
        assertEquals("HOST_GITHUB_CONNECTION_MISSING", unavailable["reason"])
        assertEquals("CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB", unavailable["repair_action"])
        val http = GatewayIndexSyncObservation.deferred(failure = GatewayGraphHttpFailure(503, internalCode = "private payload"))
        assertEquals(503, http["http_status"])
        assertEquals("CHECK_SAVED_INDEX", http["repair_action"])
        assertFalse(http.toString().contains("private"))
    }

    @Test fun timeoutAndUnfinishedPageTransferAreObservableWithoutMarkingCompletion() {
        assertEquals("SYNC_TIMEOUT", GatewayIndexSyncObservation.deferred()["reason"])
        assertEquals("SYNC_NOT_COMPLETED", GatewayIndexSyncObservation.deferred(completed = false)["reason"])
        val failed = GatewayIndexSyncObservation.deferred(failure = IllegalStateException("private path"))
        assertEquals("SYNC_FAILED", failed["reason"])
        assertFalse(failed.toString().contains("private path"))
    }
}
