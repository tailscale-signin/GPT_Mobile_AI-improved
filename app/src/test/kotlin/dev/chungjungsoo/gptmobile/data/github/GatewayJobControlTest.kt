package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCancelResult
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJobControlTest {
    @Test
    fun confirmationReviewIsReadOnlyAndFindsOriginalJobAcrossCatalogPages() = runTest {
        val fixture = Fixture()
        coEvery { fixture.history.catalog(fixture.config, null) } returns fixture.page(emptyList()).copy(hasMore = true, nextCursor = "next")
        coEvery { fixture.history.catalog(fixture.config, "next") } returns fixture.page()

        val review = fixture.control.inspect(fixture.record)

        assertEquals(fixture.record, review.record)
        assertEquals(null, review.observedResult)
        coVerify(exactly = 1) { fixture.repository.originalRoute("original-connection", fixture.config.apiUrl) }
        fixture.verifyNoStop()
    }

    @Test
    fun changedGatewayAccountProfileOrConversationCannotAuthorizeStop() = runTest {
        for (change in 0..4) {
            val fixture = Fixture()
            val page = when (change) {
                0 -> fixture.page().copy(gatewayId = "different-gateway")
                1 -> fixture.page().copy(principalId = "different-account")
                2 -> fixture.page(listOf(fixture.summary.copy(profileId = "different-profile")))
                3 -> fixture.page(listOf(fixture.summary.copy(conversationId = "different-conversation")))
                else -> fixture.page(listOf(fixture.summary.copy(model = "different-model")))
            }
            coEvery { fixture.history.catalog(fixture.config, null) } returns page
            assertEquals(GatewayJobStopOutcome.UNKNOWN, fixture.control.stop(fixture.review).outcome)
            fixture.verifyNoStop()
        }
    }

    @Test
    fun editedEndpointAndRemovedCancelPermissionFailClosed() = runTest {
        val changed = Fixture()
        coEvery { changed.repository.originalRoute(any(), any()) } throws IllegalStateException("Restore the original address")
        assertEquals(GatewayJobStopOutcome.UNKNOWN, changed.control.stop(changed.review).outcome)
        changed.verifyNoStop()

        val revoked = Fixture()
        coEvery { revoked.history.catalog(revoked.config, null) } returns revoked.page(listOf(revoked.summary.copy(allowedActions = listOf("open"))))
        assertEquals(GatewayJobStopOutcome.UNAVAILABLE, revoked.control.stop(revoked.review).outcome)
        revoked.verifyNoStop()

        val unsupported = Fixture()
        coEvery { unsupported.gateway.getCapabilities(unsupported.config, true) } returns GatewayCapabilities(supportsCancellation = false)
        assertEquals(GatewayJobStopOutcome.UNAVAILABLE, unsupported.control.stop(unsupported.review).outcome)
        unsupported.verifyNoStop()
    }

    @Test
    fun freshTerminalOrAlreadyRequestedStateIsReconciledWithoutAnotherPost() = runTest {
        for ((state, requested, expected) in listOf(
            Triple("completed", false, GatewayJobStopOutcome.ALREADY_TERMINAL),
            Triple("cancelled", false, GatewayJobStopOutcome.CONFIRMED),
            Triple("needs_attention", true, GatewayJobStopOutcome.REQUESTED)
        )) {
            val fixture = Fixture()
            coEvery { fixture.history.catalog(fixture.config, null) } returns fixture.page(listOf(fixture.summary.copy(state = state, cancellationRequested = requested)))
            assertEquals(expected, fixture.control.stop(fixture.review).outcome)
            fixture.verifyNoStop()
        }
    }

    @Test
    fun ordinaryStopKeepsOriginalRouteAndDistinguishesEveryServerOutcome() = runTest {
        for ((response, expected) in listOf(
            GatewayCancelResult("job", false, cancellationOutcome = "requested") to GatewayJobStopOutcome.REQUESTED,
            GatewayCancelResult("job", true, cancellationOutcome = "confirmed") to GatewayJobStopOutcome.CONFIRMED,
            GatewayCancelResult("job", false, status = "completed") to GatewayJobStopOutcome.ALREADY_TERMINAL,
            GatewayCancelResult("wrong-job", true, cancellationOutcome = "confirmed") to GatewayJobStopOutcome.UNKNOWN,
            null to GatewayJobStopOutcome.UNKNOWN
        )) {
            val fixture = Fixture()
            coEvery { fixture.gateway.cancelJob("job", fixture.mutationConfig) } returns response
            assertEquals(expected, fixture.control.stop(fixture.review).outcome)
            coVerify(exactly = 1) { fixture.gateway.cancelJob("job", match { it.apiUrl == fixture.record.endpoint && it.profileUid == fixture.record.connectionProfileUid && it.token == "original-credential" }) }
            coVerify(exactly = 0) { fixture.engineering.control(any(), any(), any()) }
        }
    }

    @Test
    fun explicitRetryReconcilesALostStopReceiptBeforeConsideringAnotherPost() = runTest {
        val fixture = Fixture()
        coEvery { fixture.gateway.cancelJob("job", fixture.mutationConfig) } throws IOException("Lost stop response")
        assertEquals(GatewayJobStopOutcome.UNKNOWN, fixture.control.stop(fixture.review).outcome)

        coEvery { fixture.history.catalog(fixture.config, null) } returns fixture.page(listOf(fixture.summary.copy(state = "cancelled", revision = 9)))
        assertEquals(GatewayJobStopOutcome.CONFIRMED, fixture.control.stop(fixture.review).outcome)
        coVerify(exactly = 1) { fixture.gateway.cancelJob("job", fixture.mutationConfig) }
    }

    @Test
    fun engineeringStopUsesFreshRevisionAndCannotDispatchMergeOrOrdinaryCancel() = runTest {
        val fixture = Fixture(engineeringJob = true)
        val latest = fixture.engineeringJob(revision = 21)
        coEvery { fixture.engineering.get(fixture.config, latest.id, -1) } returns latest
        coEvery { fixture.engineering.control(fixture.mutationConfig, latest, "cancel") } returns latest.copy(state = "CANCELLING", revision = 22)

        assertEquals(GatewayJobStopOutcome.REQUESTED, fixture.control.stop(fixture.review).outcome)

        coVerify(exactly = 1) { fixture.engineering.control(fixture.mutationConfig, match { it.revision == 21 && it.id == fixture.record.remoteJobId }, "cancel") }
        coVerify(exactly = 0) { fixture.engineering.control(any(), any(), match { it != "cancel" }) }
        coVerify(exactly = 0) { fixture.gateway.cancelJob(any(), any()) }
    }

    @Test
    fun engineeringCompletionRaceAndMismatchedIdentityNeverDispatchStop() = runTest {
        val finished = Fixture(engineeringJob = true)
        coEvery { finished.engineering.get(finished.config, finished.record.remoteJobId, -1) } returns finished.engineeringJob().copy(state = "COMPLETED")
        assertEquals(GatewayJobStopOutcome.ALREADY_TERMINAL, finished.control.stop(finished.review).outcome)
        finished.verifyNoStop()

        val changed = Fixture(engineeringJob = true)
        coEvery { changed.engineering.get(changed.config, changed.record.remoteJobId, -1) } returns changed.engineeringJob().copy(principalId = "other-account")
        assertEquals(GatewayJobStopOutcome.UNKNOWN, changed.control.stop(changed.review).outcome)
        changed.verifyNoStop()
    }

    @Test
    fun nonAdvancingCatalogCannotLoopOrSendStop() = runTest {
        val fixture = Fixture()
        coEvery { fixture.history.catalog(fixture.config, any()) } returns fixture.page(emptyList()).copy(hasMore = true, nextCursor = "same")
        assertTrue(runCatching { fixture.control.inspect(fixture.record) }.isFailure)
        coVerify(exactly = 2) { fixture.history.catalog(fixture.config, any()) }
        fixture.verifyNoStop()
    }

    @Test
    fun identityOrCredentialChangingAfterReviewCannotDispatchStop() = runTest {
        for (engineeringJob in listOf(false, true)) {
            val identityChanged = Fixture(engineeringJob)
            if (engineeringJob) coEvery { identityChanged.engineering.get(identityChanged.config, identityChanged.summary.jobId, -1) } returns identityChanged.engineeringJob()
            coEvery { identityChanged.history.identity(identityChanged.config) } returns GatewayScopeIdentity("different-installation", "owner")
            assertEquals(GatewayJobStopOutcome.UNKNOWN, identityChanged.control.stop(identityChanged.review).outcome)
            identityChanged.verifyNoStop()

            val credentialChanged = Fixture(engineeringJob)
            if (engineeringJob) coEvery { credentialChanged.engineering.get(credentialChanged.config, credentialChanged.summary.jobId, -1) } returns credentialChanged.engineeringJob()
            coEvery { credentialChanged.repository.originalRoute(any(), any()) } returnsMany listOf(
                GatewayJobRoute("original-connection", credentialChanged.config.apiUrl, credentialChanged.config),
                GatewayJobRoute("original-connection", credentialChanged.config.apiUrl, credentialChanged.config.copy(token = "changed-credential"))
            )
            assertEquals(GatewayJobStopOutcome.UNKNOWN, credentialChanged.control.stop(credentialChanged.review).outcome)
            credentialChanged.verifyNoStop()
        }
    }

    private class Fixture(engineeringJob: Boolean = false) {
        val repository = mockk<GatewayJobRepository>()
        val history = mockk<GatewayJobClient>()
        val gateway = mockk<GatewayAPI>()
        val engineering = mockk<GatewayEngineeringClient>()
        val config = ProviderRequestConfig("http://original-gateway:8090/v1", "original-credential", profileUid = "original-connection")
        val identity = GatewayScopeIdentity("gateway", "owner")
        val mutationConfig = config.withGatewayIdentity(identity)
        val summary = GatewayJobSummary(
            jobId = if (engineeringJob) "eng_" + "a".repeat(32) else "job",
            jobKind = if (engineeringJob) "engineering" else "generation",
            conversationId = "conversation",
            profileId = "original-profile",
            model = "original-model",
            repository = if (engineeringJob) "owner/repository" else null,
            branch = if (engineeringJob) "work" else null,
            base = if (engineeringJob) "main" else null,
            state = "running",
            revision = 7,
            acceptedAt = 1.0,
            allowedActions = listOf("open", "cancel")
        )
        val record = GatewayJobRecord(gatewayIdentityKey("gateway", "owner", summary.jobKind, summary.jobId), "gateway", "owner", summary.jobKind, summary.jobId, gatewayIdentityKey("gateway", "owner", "conversation"), "original-connection", config.apiUrl, "original-profile", "snapshot", 1000)
        val review = GatewayJobStopReview(record, summary)
        val control = GatewayJobControl(repository, history, gateway, engineering)

        init {
            every { repository.summary(record) } returns summary
            coEvery { repository.originalRoute("original-connection", config.apiUrl) } returns GatewayJobRoute("original-connection", config.apiUrl, config)
            coEvery { history.catalog(config, null) } returns page()
            coEvery { history.identity(config) } returns identity
            coEvery { gateway.getCapabilities(config, true) } returns GatewayCapabilities(supportsCancellation = true)
        }

        fun page(items: List<GatewayJobSummary> = listOf(summary)) = GatewayJobCatalog(gatewayId = "gateway", principalId = "owner", serverNow = 2.0, items = items, complete = true)

        fun engineeringJob(revision: Int = 8) = GatewayEngineeringJob(summary.jobId, "owner/repository", "work", state = "RUNNING", revision = revision, createdAt = 1.0, deadline = 1000.0, message = "Working", gatewayId = "gateway", principalId = "owner", conversationId = "conversation")

        fun verifyNoStop() {
            coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
            coVerify(exactly = 0) { engineering.control(any(), any(), any()) }
        }
    }
}
