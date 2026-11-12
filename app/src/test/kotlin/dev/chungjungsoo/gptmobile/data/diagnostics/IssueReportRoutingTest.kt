package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportRoutingTest {
    @get:Rule val files = TemporaryFolder()
    private val connections = mockk<ToolConnectionRepository>()
    private val auth = mockk<GitHubPublicAuthCoordinator>()
    private val connection = ToolConnection("saved-github", "GitHub", "github", "GITHUB", "https://api.github.com", "OAUTH", "encrypted-reference", "registered-client")
    private val account = GitHubPublicAuthCoordinator.Account("42", "approved-author")
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Approved report", description = "Original text")
    private val route = IssueReportRoute(IssueReportRoute.Kind.GITHUB, connectionUid = connection.connectionUid, accountId = account.id, accountLogin = account.login)

    private fun approved(): IssueReportOutbox = IssueReportOutbox(files.newFolder()).also {
        it.approve(report)
        it.bindRoute(report.reportId, route)
    }

    @Test fun aBuildWithoutTheServiceRouteDoesNotResumeAnApprovedServiceWriteOrClearUncertainty() = runTest {
        val approvedRoute = IssueReportRoute(IssueReportRoute.Kind.SERVICE, origin = "https://approved-reports.example.invalid")
        mockkObject(IssueReportRouting.Companion)
        mockkConstructor(IssueReportSubmission::class)
        try {
            every { IssueReportRouting.serviceRoute() } returns null
            for (attempted in listOf(false, true)) {
                val outbox = IssueReportOutbox(files.newFolder())
                outbox.approve(report)
                outbox.bindRoute(report.reportId, approvedRoute)
                if (attempted) outbox.dispatching(report.reportId, approvedRoute)
                val result = IssueReportRouting(connections, auth).send(outbox, report.reportId) as IssueReportSubmission.Failure
                assertEquals("REPORT_DESTINATION_UNAVAILABLE", result.code)
                assertFalse(result.automaticRetry)
                assertTrue(result.retryable)
                outbox.failed(report.reportId, result, automaticRetry = false)
                val retained = requireNotNull(outbox.load(report.reportId))
                assertEquals(approvedRoute, retained.route)
                assertEquals(report.approvedPayload(), retained.payload)
                assertEquals(attempted, retained.mayHaveBeenSubmitted)
            }
            verify(exactly = 0) { anyConstructed<IssueReportSubmission>().send(any(), any(), any()) }
        } finally {
            unmockkConstructor(IssueReportSubmission::class)
            unmockkObject(IssueReportRouting.Companion)
        }
    }

    @Test fun accountChoicesExcludeReadOnlyEnterpriseMissingAndUnverifiedCredentials() = runTest {
        val readOnly = connection.copy(connectionUid = "read-only", toolPolicy = "READ_ONLY")
        val enterprise = connection.copy(connectionUid = "enterprise", endpointUrl = "https://enterprise.project.org")
        val missing = connection.copy(connectionUid = "missing", secretRef = null)
        val unverified = connection.copy(connectionUid = "unverified")
        coEvery { connections.listConnections() } returns listOf(readOnly, enterprise, missing, unverified, connection)
        coEvery { auth.connectedAccount(connection.connectionUid) } returns account
        coEvery { auth.connectedAccount(unverified.connectionUid) } returns null
        val choices = IssueReportRouting(connections, auth).options().filter { it.kind == IssueReportRoute.Kind.GITHUB }
        assertEquals(listOf(route), choices)
        coVerify(exactly = 0) { auth.accessToken(any()) }
        coVerify(exactly = 0) { auth.connectedAccount(readOnly.connectionUid) }
        coVerify(exactly = 0) { auth.connectedAccount(enterprise.connectionUid) }
    }

    @Test fun aChangedAccountAfterRefreshDoesNotUseTheNewAuthorsCredential() = runTest {
        val outbox = approved()
        coEvery { connections.getConnection(connection.connectionUid) } returns connection
        coEvery { auth.accessToken(connection) } returns "fixture-credential"
        coEvery { auth.connectedAccount(connection.connectionUid) } returns account.copy(id = "99", login = "other-author")
        val result = IssueReportRouting(connections, auth).send(outbox, report.reportId) as IssueReportSubmission.Failure
        assertEquals("REPORT_GITHUB_ACCOUNT_CHANGED", result.code)
        assertFalse(result.automaticRetry)
        assertFalse(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
        assertEquals(route, outbox.load(report.reportId)?.route)
    }

    @Test fun removedOrReadOnlyConnectionsStopBeforeCredentialUse() = runTest {
        for (current in listOf(null, connection.copy(toolPolicy = "READ_ONLY"), connection.copy(endpointUrl = "https://enterprise.project.org"))) {
            val outbox = approved()
            coEvery { connections.getConnection(connection.connectionUid) } returns current
            val result = IssueReportRouting(connections, auth).send(outbox, report.reportId) as IssueReportSubmission.Failure
            assertEquals("REPORT_GITHUB_SIGN_IN_REQUIRED", result.code)
            assertFalse(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
        }
        coVerify(exactly = 0) { auth.accessToken(any()) }
    }

    @Test fun aConnectionChangeDuringCredentialRefreshStopsBeforeDispatch() = runTest {
        val outbox = approved()
        coEvery { connections.getConnection(connection.connectionUid) } returnsMany listOf(connection, connection.copy(toolPolicy = "READ_ONLY"))
        coEvery { auth.accessToken(connection) } returns "fixture-credential"
        coEvery { auth.connectedAccount(connection.connectionUid) } returns account
        val result = IssueReportRouting(connections, auth).send(outbox, report.reportId) as IssueReportSubmission.Failure
        assertEquals("REPORT_GITHUB_SIGN_IN_REQUIRED", result.code)
        assertFalse(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
    }

    @Test fun verifiedAccountUsesOnlyItsApprovedRouteAndPersistsDispatchBeforeTransport() = runTest {
        val outbox = approved()
        coEvery { connections.getConnection(connection.connectionUid) } returns connection
        coEvery { auth.accessToken(connection) } returns "fixture-credential"
        coEvery { auth.connectedAccount(connection.connectionUid) } returns account
        mockkConstructor(IssueReportGitHubSubmission::class)
        try {
            every { anyConstructed<IssueReportGitHubSubmission>().send(any(), any(), any()) } answers {
                assertEquals(route, firstArg<IssueReportOutbox.Entry>().route)
                assertEquals("fixture-credential", secondArg<String>())
                thirdArg<() -> Unit>().invoke()
                assertTrue(requireNotNull(outbox.load(report.reportId)).mayHaveBeenSubmitted)
                IssueReportSubmission.Confirmed(742)
            }
            assertEquals(IssueReportSubmission.Confirmed(742), IssueReportRouting(connections, auth).send(outbox, report.reportId))
            assertEquals(report.approvedPayload(), outbox.load(report.reportId)?.payload)
        } finally {
            unmockkConstructor(IssueReportGitHubSubmission::class)
        }
    }
}
