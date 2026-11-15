package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import androidx.lifecycle.ViewModelStore
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayJobsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayJobRecoveryApprovalTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun currentRecoveryReviewIsReadOnlyAndConfirmationPostsItsExactRevisionOnce() = runTest(dispatcher) {
        Fixture().use { fixture ->
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            assertEquals(fixture.currentJob, reviewed)
            assertEquals(1, fixture.reads.size)
            fixture.assertNoMutation()
            coVerify(exactly = 0) { fixture.history.identity(any()) }

            fixture.control.approveRecovery(fixture.record, reviewed)

            assertEquals(2, fixture.reads.size)
            assertEquals(1, fixture.posts.size)
            val sent = fixture.posts.single()
            assertEquals("${fixture.config.apiUrl}/gateway/engineering/jobs/${reviewed.id}/approve", sent.url.toString())
            assertEquals("Bearer original-credential", sent.headers[HttpHeaders.Authorization])
            assertEquals("approve-and-merge", sent.headers["X-Gateway-User-Action"])
            assertEquals("gateway", sent.headers["X-Gateway-Installation-ID"])
            assertEquals("owner", sent.headers["X-Gateway-Principal-ID"])
            assertEquals(
                Json.parseToJsonElement("""{"revision":12,"pr":7,"head_sha":"${"b".repeat(40)}"}"""),
                Json.parseToJsonElement((sent.body as TextContent).text)
            )
            fixture.assertNoOtherMutation()
        }
    }

    @Test fun missingJobRecoveryParentOrApprovalPermissionCannotProduceAnActionableReview() = runTest(dispatcher) {
        for (change in 0..3) {
            Fixture().use { fixture ->
                fixture.catalog = when (change) {
                    0 -> fixture.catalog.copy(items = emptyList())
                    1 -> fixture.catalog.copy(items = listOf(fixture.summary.copy(recoveryOf = null)))
                    2 -> fixture.catalog.copy(items = listOf(fixture.summary.copy(allowedActions = listOf("open"))))
                    else -> fixture.catalog.copy(items = listOf(fixture.summary.copy(revision = fixture.summary.revision - 1)))
                }

                val failure = runCatching { fixture.control.inspectRecoveryApproval(fixture.record) }.exceptionOrNull()

                assertTrue("Catalog refusal $change must be an observed guard", failure is IllegalStateException)
                assertEquals(0, fixture.reads.size)
                fixture.assertNoMutation()
            }
        }
    }

    @Test fun incompleteOrMismatchedRecoverySnapshotsAreRejectedBeforeApproval() = runTest(dispatcher) {
        val changes: List<(GatewayEngineeringJob) -> GatewayEngineeringJob> = listOf(
            { it.copy(id = "eng_" + "c".repeat(32)) },
            { it.copy(gatewayId = "other-gateway") },
            { it.copy(principalId = "other-account") },
            { it.copy(conversationId = "other-conversation") },
            { it.copy(repository = "owner/other") },
            { it.copy(branch = "other-branch") },
            { it.copy(revision = 6) },
            { it.copy(mergeReady = false) },
            { it.copy(pr = null) },
            { it.copy(headSha = null) },
            { it.copy(headSha = "not-a-commit") },
            { it.copy(recoveryOf = null) },
            { it.copy(recoveryOf = "eng_" + "d".repeat(32)) },
            { it.copy(executionBindingId = null) },
            { it.copy(executionBindingId = " ") }
        )
        for ((index, change) in changes.withIndex()) {
            Fixture().use { fixture ->
                fixture.currentJob = change(fixture.currentJob)

                val failure = runCatching { fixture.control.inspectRecoveryApproval(fixture.record) }.exceptionOrNull()

                assertTrue("Recovery snapshot $index must be refused", failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains(if (index < 7) "job identity changed" else "exact recovery revision"))
                assertEquals(1, fixture.reads.size)
                fixture.assertNoMutation()
            }
        }
    }

    @Test fun aChangedRecoveryCannotUseTheEarlierReviewedApproval() = runTest(dispatcher) {
        val changes: List<(GatewayEngineeringJob) -> GatewayEngineeringJob> = listOf(
            { it.copy(revision = it.revision + 1) },
            { it.copy(pr = 8) },
            { it.copy(headSha = "c".repeat(40)) },
            { it.copy(recoveryOf = "eng_" + "d".repeat(32)) },
            { it.copy(executionBindingId = "binding-replacement") }
        )
        for (change in changes) {
            Fixture().use { fixture ->
                val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
                fixture.currentJob = change(reviewed)
                fixture.catalog = fixture.catalog.copy(items = listOf(fixture.summary.copy(recoveryOf = fixture.currentJob.recoveryOf)))

                val failure = runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("The recovery changed"))
                assertEquals(2, fixture.reads.size)
                fixture.assertNoMutation()
            }
        }
        Fixture().use { fixture ->
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            fixture.catalog = fixture.catalog.copy(items = listOf(fixture.summary.copy(allowedActions = listOf("open"))))
            assertTrue(runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull() is IllegalStateException)
            assertEquals(1, fixture.reads.size)
            fixture.assertNoMutation()
        }
    }

    @Test fun accountAndConnectionAreRevalidatedImmediatelyBeforeApprovalDispatch() = runTest(dispatcher) {
        for (change in 0..3) {
            Fixture().use { fixture ->
                val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
                coEvery { fixture.history.identity(fixture.config) } coAnswers {
                    when (change) {
                        0 -> fixture.identity.copy(gatewayId = "other-gateway")
                        1 -> fixture.identity.copy(principalId = "other-account")
                        else -> {
                            val changed = if (change == 2) fixture.config.copy(token = "replacement-credential") else fixture.config.copy(apiUrl = "https://other.invalid/v1")
                            fixture.currentRoute = fixture.route.copy(config = changed)
                            fixture.identity
                        }
                    }
                }

                val failure = runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull()

                assertTrue("Mutation boundary $change must be refused", failure is IllegalStateException)
                coVerify(exactly = 1) { fixture.history.identity(fixture.config) }
                fixture.assertNoMutation()
            }
        }
    }

    @Test fun cancellationDuringTheFinalIdentityReadNeverDispatchesApproval() = runTest(dispatcher) {
        Fixture().use { fixture ->
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            val identityRead = CompletableDeferred<Unit>()
            coEvery { fixture.history.identity(fixture.config) } coAnswers {
                identityRead.complete(Unit)
                awaitCancellation()
            }
            val approving = async { fixture.control.approveRecovery(fixture.record, reviewed) }
            identityRead.await()

            approving.cancelAndJoin()

            assertTrue(approving.isCancelled)
            assertEquals(2, fixture.reads.size)
            fixture.assertNoMutation()
        }
    }

    @Test fun lostMalformedAndMismatchedApprovalAcknowledgementsAreNeverAutomaticallyReplayed() = runTest(dispatcher) {
        for (change in 0..7) {
            Fixture().use { fixture ->
                val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
                fixture.approvalResponse = {
                    when (change) {
                        0 -> throw IOException("Approval response was lost")
                        1 -> "{"
                        else -> Json.encodeToString(
                            when (change) {
                                2 -> reviewed.copy(id = "eng_" + "c".repeat(32), revision = 13)
                                3 -> reviewed.copy(revision = 12)
                                4 -> reviewed.copy(revision = 11)
                                5 -> reviewed.copy(revision = 13, gatewayId = "other-gateway")
                                6 -> reviewed.copy(revision = 13, principalId = "other-account")
                                else -> reviewed.copy(revision = 13, executionBindingId = "other-binding")
                            }
                        )
                    }
                }

                val failure = runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull()

                when (change) {
                    0 -> assertTrue(failure is IOException)
                    1 -> assertTrue(failure is SerializationException)
                    else -> {
                        assertTrue(failure is IllegalStateException)
                        assertTrue(failure?.message.orEmpty().contains("Approval outcome is unknown"))
                    }
                }
                assertEquals("Uncertain response $change must leave exactly one attempted POST", 1, fixture.posts.size)
                assertEquals(2, fixture.reads.size)
                fixture.assertNoOtherMutation()
            }
        }
    }

    @Test fun approvalRefusalGuidanceNamesApprovalWhileStopReviewKeepsItsOwnAction() = runTest(dispatcher) {
        for (boundary in 0..4) {
            Fixture().use { fixture ->
                val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
                when (boundary) {
                    0 -> fixture.catalog = fixture.catalog.copy(items = emptyList())
                    1 -> fixture.catalog = fixture.catalog.copy(principalId = "other-account")
                    2 -> fixture.currentJob = reviewed.copy(id = "eng_" + "c".repeat(32))
                    3 -> coEvery { fixture.history.identity(fixture.config) } returns fixture.identity.copy(principalId = "other-account")
                    else -> coEvery { fixture.history.identity(fixture.config) } coAnswers {
                        fixture.currentRoute = fixture.route.copy(config = fixture.config.copy(token = "replacement-credential"))
                        fixture.identity
                    }
                }

                val failure = runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull()
                val message = failure?.message.orEmpty()

                assertTrue("Boundary $boundary must name the requested approval: $message", message.contains("approv", ignoreCase = true))
                assertFalse("Approval must not describe a Stop action: $message", Regex("\\bstop(?:ping)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(message))
                fixture.assertNoMutation()
            }
        }
        Fixture().use { fixture ->
            fixture.catalog = fixture.catalog.copy(items = emptyList())
            val failure = runCatching { fixture.control.inspect(fixture.record) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("No Stop request was sent"))
            fixture.assertNoMutation()
        }
    }

    @Test fun viewModelReviewRequiresExplicitConfirmationAndCoalescesBusyConfirmations() = runTest(dispatcher) {
        Fixture().use { fixture ->
            val model = fixture.bindModel(dispatcher)
            val row = model.state.value.rows.single()
            model.confirmRecoveryApproval()
            fixture.assertNoMutation()
            model.showRecoveryApproval(row)
            model.state.first { it.approval?.job != null && it.approval?.busy == false }
            fixture.assertNoMutation()
            val posted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.approvalResponse = {
                posted.complete(Unit)
                release.await()
                Json.encodeToString(fixture.currentJob.copy(revision = 13, state = "MERGING"))
            }

            model.confirmRecoveryApproval()
            posted.await()
            repeat(3) { model.confirmRecoveryApproval() }
            assertTrue(model.state.value.approval?.busy == true)
            assertEquals(1, fixture.posts.size)
            release.complete(Unit)
            model.state.first { it.approval == null && it.loaded }

            assertEquals(1, fixture.posts.size)
            assertNull(model.state.value.approval)
            coVerify(exactly = 1) { fixture.repository.refresh("selected-profile", null) }
            fixture.assertNoOtherMutation()
        }
    }

    @Test fun viewModelClearsUncertainApprovalAndRequiresAFreshReviewBeforeAnotherExplicitPost() = runTest(dispatcher) {
        for (lostResponse in listOf(true, false)) {
            Fixture().use { fixture ->
                val model = fixture.bindModel(dispatcher)
                val row = model.state.value.rows.single()
                model.showRecoveryApproval(row)
                model.state.first { it.approval?.job != null && it.approval?.busy == false }
                fixture.approvalResponse = {
                    if (lostResponse) throw IOException("Approval response was lost")
                    Json.encodeToString(fixture.currentJob.copy(revision = 13, executionBindingId = "other-binding"))
                }

                model.confirmRecoveryApproval()
                model.state.first { it.approval?.error != null && it.approval?.busy == false }
                assertNull(model.state.value.approval?.job)
                repeat(3) { model.confirmRecoveryApproval() }
                assertEquals(1, fixture.posts.size)
                coVerify(exactly = 0) { fixture.repository.refresh(any(), any()) }

                model.dismissRecoveryApproval()
                fixture.currentJob = fixture.currentJob.copy(revision = 14, headSha = "c".repeat(40))
                fixture.approvalResponse = { Json.encodeToString(fixture.currentJob.copy(revision = 15, state = "MERGING")) }
                model.showRecoveryApproval(row)
                model.state.first { it.approval?.job?.revision == 14 && it.approval?.busy == false }
                assertEquals(1, fixture.posts.size)
                model.confirmRecoveryApproval()
                model.state.first { it.approval == null && it.loaded }

                assertEquals(2, fixture.posts.size)
                assertEquals(
                    Json.parseToJsonElement("""{"revision":14,"pr":7,"head_sha":"${"c".repeat(40)}"}"""),
                    Json.parseToJsonElement((fixture.posts.last().body as TextContent).text)
                )
                fixture.assertNoOtherMutation()
            }
        }
    }

    private class Fixture : Closeable {
        val repository = mockk<GatewayJobRepository>()
        val history = mockk<GatewayJobClient>()
        val gateway = mockk<GatewayAPI>()
        val conversations = mockk<GatewayConversationRepository>()
        private val settings = mockk<SettingRepository>()
        private val owner = ViewModelStore()
        val config = ProviderRequestConfig("https://approval.gateway.invalid/v1", "original-credential", profileUid = "original-connection")
        val route = GatewayJobRoute("original-connection", config.apiUrl, config)
        var currentRoute = route
        val identity = GatewayScopeIdentity("gateway", "owner")
        val summary = GatewayJobSummary(
            jobId = "eng_" + "a".repeat(32),
            jobKind = "engineering",
            conversationId = "conversation",
            profileId = "original-profile",
            model = "original-model",
            repository = "owner/repository",
            branch = "recovery-work",
            base = "main",
            state = "MERGE_READY",
            revision = 7,
            acceptedAt = 1.0,
            allowedActions = listOf("open", "approve"),
            recoveryOf = "eng_" + "f".repeat(32)
        )
        val record = GatewayJobRecord(gatewayIdentityKey("gateway", "owner", summary.jobKind, summary.jobId), "gateway", "owner", summary.jobKind, summary.jobId, gatewayIdentityKey("gateway", "owner", "conversation"), "original-connection", config.apiUrl, "original-profile", "snapshot", 1000)
        var catalog = GatewayJobCatalog(gatewayId = "gateway", principalId = "owner", serverNow = 2.0, items = listOf(summary), complete = true)
        var currentJob = GatewayEngineeringJob(
            summary.jobId,
            "owner/repository",
            "recovery-work",
            state = "MERGE_READY",
            revision = 12,
            createdAt = 1.0,
            deadline = 1000.0,
            message = "Review this recovery",
            pr = 7,
            headSha = "b".repeat(40),
            mergeReady = true,
            executionBindingId = "binding-original",
            gatewayId = "gateway",
            principalId = "owner",
            conversationId = "conversation",
            base = summary.base,
            recoveryOf = summary.recoveryOf
        )
        val reads = mutableListOf<HttpRequestData>()
        val posts = mutableListOf<HttpRequestData>()
        var approvalResponse: suspend () -> String = { Json.encodeToString(currentJob.copy(revision = currentJob.revision + 1, state = "MERGING")) }
        private val http = HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Get) {
                    reads += request
                    assertEquals("/v1/gateway/engineering/jobs/${summary.jobId}", request.url.encodedPath)
                    assertEquals("-1", request.url.parameters["after_revision"])
                    respond(Json.encodeToString(currentJob))
                } else {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/v1/gateway/engineering/jobs/${summary.jobId}/approve", request.url.encodedPath)
                    posts += request
                    respond(approvalResponse())
                }
            }
        ) {
            install(HttpTimeout)
            followRedirects = false
        }
        val control = GatewayJobControl(repository, history, gateway, GatewayEngineeringClient(http))

        init {
            every { repository.summary(record) } returns summary
            coEvery { repository.originalRoute("original-connection", config.apiUrl) } answers { currentRoute }
            coEvery { history.catalog(config, null) } answers { catalog }
            coEvery { history.identity(config) } returns identity
        }

        suspend fun bindModel(dispatcher: CoroutineDispatcher): GatewayJobsViewModel {
            every { repository.observeConfiguration("selected-profile") } returns flowOf(route)
            coEvery { repository.cachedScope(route) } returns GatewayVerifiedScope("a".repeat(64), "gateway", "owner", 1)
            every { repository.observe(route) } returns flowOf(listOf(record))
            coEvery { settings.fetchPlatformV2s() } returns emptyList()
            coEvery { repository.route("selected-profile", requireEnabled = false) } returns route
            coEvery { repository.refresh("selected-profile", null) } answers { catalog }
            every { repository.invalidateSavedWorkContract() } just Runs
            return GatewayJobsViewModel(repository, conversations, settings, control, dispatcher).also { model ->
                owner.put("jobs", model)
                model.bind("selected-profile")
                model.state.first { it.configured && it.rows.size == 1 }
            }
        }

        fun assertNoMutation() {
            assertTrue(posts.isEmpty())
            assertNoOtherMutation()
        }

        fun assertNoOtherMutation() {
            coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
            coVerify(exactly = 0) { conversations.attach(any()) }
            coVerify(exactly = 0) { conversations.queueInstruction(any(), any(), any()) }
        }

        override fun close() {
            owner.clear()
            http.close()
        }
    }
}
