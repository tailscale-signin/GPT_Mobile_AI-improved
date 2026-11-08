package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCancelResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRunCancellationTest {
    private val run = AgentRun("run", 1, 2, 3, "selected", "LLAMA", "model", AgentRunStatus.CANCELED, gatewayJobId = "saved-job", gatewayBaseUrl = "http://gateway:8090")
    private val profile = PlatformV2(uid = "selected", name = "Selected profile", apiUrl = "http://gateway:8090", model = "model")

    @Test
    fun observerInterruptionAndSupersessionNeverDispatchRemoteStop() {
        CancellationOrigin.entries.forEach { assertFalse(requestsRemoteGatewayCancellation(it, interrupted = true)) }
        assertFalse(requestsRemoteGatewayCancellation(CancellationOrigin.PROCESS_INTERRUPTION, interrupted = false))
        assertFalse(requestsRemoteGatewayCancellation(CancellationOrigin.SUPERSEDED_GENERATION, interrupted = false))
        assertFalse(requestsRemoteGatewayCancellation(CancellationOrigin.CHAT_DELETED, interrupted = false))
        assertFalse(requestsRemoteGatewayCancellation(CancellationOrigin.UNKNOWN, interrupted = false))
        assertTrue(requestsRemoteGatewayCancellation(CancellationOrigin.USER_WHOLE_RUN, interrupted = false))
    }

    @Test
    fun explicitStopUsesTheRetainedJobAndReportsRequestedRatherThanConfirmed() = runTest {
        val gateway = mockk<GatewayAPI>()
        coEvery { gateway.cancelJob("saved-job", any()) } returns GatewayCancelResult("saved-job", false, cancellationOutcome = "requested")

        val receipt = stopSavedGatewayRun(run, profile, gateway)

        assertEquals("GATEWAY_CANCELLATION_REQUESTED", receipt.code)
        coVerify(exactly = 1) { gateway.cancelJob("saved-job", match { it.apiUrl == "http://gateway:8090" && it.profileUid == "selected" }) }
    }

    @Test
    fun editedConnectionCannotRedirectAStopToAnotherGateway() = runTest {
        val gateway = mockk<GatewayAPI>()
        val receipt = stopSavedGatewayRun(run, profile.copy(apiUrl = "http://another-gateway:8090"), gateway)
        assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", receipt.code)
        coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
    }

    @Test
    fun lostOrMismatchedReceiptCannotClaimCancellation() = runTest {
        for (response in listOf(null, GatewayCancelResult("different-job", true, cancellationOutcome = "confirmed"))) {
            val gateway = mockk<GatewayAPI>()
            coEvery { gateway.cancelJob(any(), any()) } returns response
            assertEquals("GATEWAY_CANCELLATION_UNCONFIRMED", stopSavedGatewayRun(run, profile, gateway).code)
        }
    }

    @Test
    fun confirmationAndAnAlreadyFinishedJobRemainDistinct() = runTest {
        val gateway = mockk<GatewayAPI>()
        coEvery { gateway.cancelJob(any(), any()) } returns GatewayCancelResult("saved-job", true, cancellationOutcome = "confirmed")
        assertEquals("GATEWAY_CANCELLATION_CONFIRMED", stopSavedGatewayRun(run, profile, gateway).code)
        coEvery { gateway.cancelJob(any(), any()) } returns GatewayCancelResult("saved-job", false, status = "completed")
        assertEquals("GATEWAY_ALREADY_TERMINAL", stopSavedGatewayRun(run, profile, gateway).code)
    }
}
