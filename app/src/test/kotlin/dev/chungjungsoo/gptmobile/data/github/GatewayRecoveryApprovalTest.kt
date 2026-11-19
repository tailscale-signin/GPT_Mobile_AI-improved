package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRecoveryApprovalTest {
    @Test
    fun reviewTraversesNestedRecoveriesWithoutReattachingTheirConversations() = runTest {
        for (descendants in listOf(0, 2)) {
            val fixture = Fixture(descendants)
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            assertEquals(fixture.leaf, reviewed)
            fixture.verifyNoMutation()

            fixture.control.approveRecovery(fixture.record, reviewed)

            coVerify(exactly = 1) { fixture.engineering.control(fixture.retainedConfig, fixture.leaf, "approve") }
            coVerify(exactly = 0) { fixture.engineering.control(any(), match { it.id != fixture.leaf.id }, any()) }
            coVerify(exactly = 0) { fixture.gateway.cancelJob(any(), any()) }
            fixture.jobs.keys.forEach { id -> coVerify(exactly = 2) { fixture.engineering.get(fixture.retainedConfig, id, -1) } }
            assertEquals("original-conversation", fixture.jobs.getValue(fixture.record.remoteJobId).conversationId)
            if (descendants > 0) {
                assertEquals("VERIFY", fixture.jobs.getValue(fixture.id(1)).state)
                assertEquals(null, fixture.jobs.getValue(fixture.id(1)).conversationId)
                assertEquals("recovery-conversation-2", reviewed.conversationId)
            }
        }
    }

    @Test
    fun eachRecoveryEdgeMustKeepItsParentAndExecutionAuthority() = runTest {
        for (change in 0..7) {
            val fixture = Fixture(2)
            val child = fixture.jobs.getValue(fixture.id(1))
            fixture.jobs[child.id] = when (change) {
                0 -> child.copy(gatewayId = "different-gateway")
                1 -> child.copy(principalId = "different-principal")
                2 -> child.copy(repository = "other/repository")
                3 -> child.copy(base = "other-base")
                4 -> child.copy(executionBindingId = "other-binding")
                5 -> child.copy(executionBinding = buildJsonObject { put("mode", "other-execution") })
                6 -> child.copy(recoveryOf = fixture.id(4))
                else -> child.copy(id = fixture.id(4))
            }
            assertTrue(runCatching { fixture.control.inspectRecoveryApproval(fixture.record) }.isFailure)
            coVerify(exactly = 0) { fixture.engineering.get(fixture.retainedConfig, fixture.id(2), -1) }
            fixture.verifyNoMutation()
        }
        val originalConversationChanged = Fixture(2)
        val root = originalConversationChanged.jobs.getValue(originalConversationChanged.id(0))
        originalConversationChanged.jobs[root.id] = root.copy(conversationId = "different-original-conversation")
        assertTrue(runCatching { originalConversationChanged.control.inspectRecoveryApproval(originalConversationChanged.record) }.isFailure)
        originalConversationChanged.verifyNoMutation()
    }

    @Test
    fun recoveryChainAllowsFiveDescendantsButRejectsCyclesAndTheSixthBeforeFetchingIt() = runTest {
        val maximum = Fixture(5)
        assertEquals(maximum.leaf, maximum.control.inspectRecoveryApproval(maximum.record))
        maximum.verifyNoMutation()

        val tooDeep = Fixture(6)
        assertTrue(runCatching { tooDeep.control.inspectRecoveryApproval(tooDeep.record) }.isFailure)
        coVerify(exactly = 6) { tooDeep.engineering.get(tooDeep.retainedConfig, any(), -1) }
        coVerify(exactly = 0) { tooDeep.engineering.get(tooDeep.retainedConfig, tooDeep.id(6), -1) }
        tooDeep.verifyNoMutation()

        val cycle = Fixture(2)
        val child = cycle.jobs.getValue(cycle.id(1))
        cycle.jobs[child.id] = child.copy(recoveryJobId = cycle.id(0))
        assertTrue(runCatching { cycle.control.inspectRecoveryApproval(cycle.record) }.isFailure)
        coVerify(exactly = 1) { cycle.engineering.get(cycle.retainedConfig, cycle.id(0), -1) }
        coVerify(exactly = 0) { cycle.engineering.get(cycle.retainedConfig, cycle.id(2), -1) }
        cycle.verifyNoMutation()
    }

    @Test
    fun freshApprovalCannotReuseAnAncestorOrChangedLeafReceipt() = runTest {
        for (change in 0..5) {
            val fixture = Fixture(2)
            var reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            fixture.jobs[reviewed.id] = when (change) {
                0 -> reviewed.copy(revision = reviewed.revision + 1)
                1 -> reviewed.copy(headSha = "b".repeat(40))
                2 -> reviewed.copy(pr = 4321)
                3 -> reviewed.copy(executionBindingId = "changed-execution")
                4 -> reviewed.copy(state = "VERIFY", mergeReady = false)
                else -> reviewed.also { reviewed = fixture.jobs.getValue(fixture.id(1)) }
            }
            assertTrue(runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.isFailure)
            fixture.verifyNoMutation()
        }
    }

    @Test
    fun approvalKeepsTheReadCredentialAndIdentityThroughTheLastMutationFence() = runTest {
        for (change in listOf("credential", "identity")) {
            val fixture = Fixture(0)
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            if (change == "credential") {
                val rotated = fixture.config.copy(token = "changed-credential")
                coEvery { fixture.repository.originalRoute("original-connection", fixture.config.apiUrl) } returnsMany listOf(
                    GatewayJobRoute("original-connection", fixture.config.apiUrl, fixture.config),
                    GatewayJobRoute("original-connection", fixture.config.apiUrl, rotated),
                    GatewayJobRoute("original-connection", fixture.config.apiUrl, rotated)
                )
                coEvery { fixture.history.identity(rotated.withGatewayIdentity(fixture.identity)) } returns fixture.identity
            } else {
                coEvery { fixture.history.identity(fixture.retainedConfig) } returns GatewayScopeIdentity("gateway", "different-principal")
            }
            assertTrue(runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.isFailure)
            fixture.verifyNoMutation()
        }
    }

    @Test
    fun lostApprovalResponseIsReconciledBeforeAnyExplicitRetry() = runTest {
        val fixture = Fixture(0)
        val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
        coEvery { fixture.engineering.control(fixture.retainedConfig, reviewed, "approve") } throws IOException("Approval response was lost")
        assertTrue(runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull() is IOException)
        fixture.jobs[reviewed.id] = reviewed.copy(state = "VERIFY", mergeReady = false, revision = reviewed.revision + 1)
        assertTrue(runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.isFailure)
        coVerify(exactly = 1) { fixture.engineering.control(any(), any(), "approve") }
        coVerify(exactly = 0) { fixture.gateway.cancelJob(any(), any()) }
    }

    @Test
    fun approvalResponseCannotClaimSuccessForChangedOwnershipOrAnUnadvancedRevision() = runTest {
        for (change in 0..6) {
            val fixture = Fixture(0)
            val reviewed = fixture.control.inspectRecoveryApproval(fixture.record)
            val response = reviewed.copy(state = "MERGE", revision = reviewed.revision + 1)
            coEvery { fixture.engineering.control(fixture.retainedConfig, reviewed, "approve") } returns when (change) {
                0 -> response.copy(gatewayId = "different-gateway")
                1 -> response.copy(principalId = "different-principal")
                2 -> response.copy(repository = "other/repository")
                3 -> response.copy(base = "other-base")
                4 -> response.copy(executionBindingId = "other-binding")
                5 -> response.copy(branch = "other-branch")
                else -> response.copy(revision = reviewed.revision)
            }
            val failure = runCatching { fixture.control.approveRecovery(fixture.record, reviewed) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Approval outcome is unknown"))
            coVerify(exactly = 1) { fixture.engineering.control(any(), any(), "approve") }
        }
    }

    private class Fixture(descendants: Int) {
        val repository = mockk<GatewayJobRepository>()
        val history = mockk<GatewayJobClient>()
        val gateway = mockk<GatewayAPI>()
        val engineering = mockk<GatewayEngineeringClient>()
        val config = ProviderRequestConfig("http://original-gateway:8090/v1", "original-credential", profileUid = "original-connection")
        val identity = GatewayScopeIdentity("gateway", "owner")
        val retainedConfig = config.withGatewayIdentity(identity)
        val summary = GatewayJobSummary(
            jobId = id(0),
            jobKind = "engineering",
            conversationId = "original-conversation",
            profileId = "original-profile",
            model = "original-model",
            repository = "owner/repository",
            branch = "work",
            base = "main",
            state = if (descendants == 0) "MERGE_READY" else "VERIFY",
            revision = 7,
            acceptedAt = 1.0,
            allowedActions = if (descendants == 0) listOf("open", "approve") else listOf("open"),
            recoveryOf = id(10).takeIf { descendants == 0 },
            recoveryJobId = id(1).takeIf { descendants > 0 }
        )
        val record = GatewayJobRecord(gatewayIdentityKey("gateway", "owner", "engineering", summary.jobId), "gateway", "owner", "engineering", summary.jobId, gatewayIdentityKey("gateway", "owner", "original-conversation"), "original-connection", config.apiUrl, "original-profile", "snapshot", 1000)
        val jobs = (0..descendants).associate { index ->
            id(index) to GatewayEngineeringJob(
                id(index),
                "owner/repository",
                if (index == 0) "work" else "recovery-$index",
                state = if (index == descendants) "MERGE_READY" else "VERIFY",
                revision = 8 + index,
                createdAt = 1.0,
                deadline = 1000.0,
                message = "Saved recovery",
                pr = 1203.takeIf { index == descendants },
                headSha = "a".repeat(40).takeIf { index == descendants },
                mergeReady = index == descendants,
                executionBindingId = "original-binding",
                executionBinding = buildJsonObject { put("mode", "owned-execution") },
                gatewayId = "gateway",
                principalId = "owner",
                conversationId = when (index) {
                    0 -> "original-conversation"
                    1 -> null
                    else -> "recovery-conversation-$index"
                },
                base = "main",
                recoveryOf = if (index == 0) summary.recoveryOf else id(index - 1),
                recoveryJobId = id(index + 1).takeIf { index < descendants }
            )
        }.toMutableMap()
        val leaf = jobs.getValue(id(descendants))
        val control = GatewayJobControl(repository, history, gateway, engineering)

        init {
            every { repository.summary(record) } returns summary
            coEvery { repository.originalRoute("original-connection", config.apiUrl) } returns GatewayJobRoute("original-connection", config.apiUrl, config)
            coEvery { history.catalog(retainedConfig, null) } returns GatewayJobCatalog(gatewayId = "gateway", principalId = "owner", serverNow = 2.0, items = listOf(summary), complete = true)
            coEvery { history.identity(retainedConfig) } returns identity
            coEvery { engineering.get(retainedConfig, any(), -1) } answers { jobs.getValue(secondArg()) }
            coEvery { engineering.control(retainedConfig, any(), "approve") } answers { secondArg<GatewayEngineeringJob>().let { it.copy(state = "MERGE", revision = it.revision + 1) } }
        }

        fun id(index: Int) = "eng_" + (index + 1).toString(16).padStart(32, '0')

        fun verifyNoMutation() {
            coVerify(exactly = 0) { engineering.control(any(), any(), any()) }
            coVerify(exactly = 0) { gateway.cancelJob(any(), any()) }
        }
    }
}
