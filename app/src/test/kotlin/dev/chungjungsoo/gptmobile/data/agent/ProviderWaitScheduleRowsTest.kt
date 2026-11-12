package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWaitScheduleRowsTest {
    private val profile = PlatformV2(uid = "limited", name = "Limited", apiUrl = FreeAiProvider.LLM7.apiUrl, model = "model", token = "saved-token", compatibleType = ClientType.FREE)
    private val codec = Json { encodeDefaults = true }

    private fun row(): ProviderWait {
        val revision = ConfiguredDispatchAuthority.profileRevision(profile)
        val authority = CapturedProviderAuthority(profile.uid, profile.model, revision, "policy", "permission", CapturedProviderAuthority.routeRevision(profile))
        val decision = ProviderLimitDecision("LLM7", profile.uid, profile.model, 30_156, "free-quota:saved", "PROVIDER_RATE_LIMITED", true, authority = authority)
        val checkpoint = ProviderWaitCheckpoint(
            chatId = 1, platform = profile.copy(token = null), registeredRevision = revision, policyRevision = "policy", permissionRevision = "permission",
            users = listOf(1), replies = listOf(emptyList()), inputDigest = "accepted-input", tools = null, stage = null, participants = listOf(profile.uid),
            decision = decision, limitPlatform = profile.copy(token = null), limitRegisteredRevision = revision
        )
        return ProviderWait("run", 7, decision.scope, decision.retryAt, codec.encodeToString(checkpoint))
    }

    private fun owner(row: ProviderWait) = ProviderWaitDeadline(row.runId, row.fence, row.retryAt)

    @Test fun repeatedMessageInvalidationsDoNotResubscribeCheckpointsOrReloadProfiles() = runTest {
        val saved = row()
        val owners = MutableSharedFlow<List<ProviderWaitDeadline>>(replay = 1)
        owners.emit(listOf(owner(saved)))
        val payload = MutableStateFlow(listOf(saved))
        val revision = MutableStateFlow(0L)
        var subscriptions = 0
        var profiles = 0
        val observed = mutableListOf<List<ProviderWaitDeadline>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owners.withCurrentProviderWaitRows { ids ->
                assertEquals(listOf(saved.runId), ids)
                subscriptions++
                payload
            }.withCurrentProviderWaitSchedules(
                loadProfiles = {
                    profiles++
                    listOf(profile)
                },
                paidRevisions = revision,
                freeRevisions = revision,
                paidDeadline = { null },
                freeDeadline = { 120_000 }
            ).collect { observed += it }
        }
        runCurrent()
        assertEquals(120_000L, observed.last().single().effectiveRetryAt)
        val count = observed.size
        repeat(20) {
            owners.emit(listOf(owner(saved).copy()))
            runCurrent()
        }
        assertEquals(1, subscriptions)
        assertEquals(1, profiles)
        assertEquals(count, observed.size)
    }

    @Test fun checkpointOnlyChangesInvalidateThePreparedAuthorityWithoutAnOwnerChange() = runTest {
        val saved = row()
        val owners = MutableStateFlow(listOf(owner(saved)))
        val payload = MutableStateFlow(listOf(saved))
        val revision = MutableStateFlow(0L)
        var subscriptions = 0
        var profiles = 0
        val observed = mutableListOf<List<ProviderWaitDeadline>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owners.withCurrentProviderWaitRows {
                subscriptions++
                payload
            }
                .withCurrentProviderWaitSchedules(
                    loadProfiles = {
                        profiles++
                        listOf(profile)
                    },
                    paidRevisions = revision,
                    freeRevisions = revision,
                    paidDeadline = { null },
                    freeDeadline = { 120_000 }
                ).collect { observed += it }
        }
        runCurrent()
        assertEquals(120_000L, observed.last().single().effectiveRetryAt)
        val checkpoint = codec.decodeFromString<ProviderWaitCheckpoint>(saved.checkpoint)
        payload.value = listOf(saved.copy(checkpoint = codec.encodeToString(checkpoint.copy(limitRegisteredRevision = "changed"))))
        runCurrent()
        assertEquals(1, subscriptions)
        assertEquals(2, profiles)
        assertEquals(saved.retryAt, observed.last().single().effectiveRetryAt)
        assertNull(observed.last().single().scheduledRetryAt)
        assertEquals(listOf(owner(saved)), owners.value)
    }

    @Test fun fullRowsMustStillMatchTheSelectedRunFenceAndOriginalDeadline() = runTest {
        val saved = row()
        val owners = MutableStateFlow(listOf(owner(saved)))
        val payload = MutableStateFlow(listOf(saved))
        val observed = mutableListOf<List<ProviderWait>>()
        var subscriptions = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owners.withCurrentProviderWaitRows {
                subscriptions++
                payload
            }.collect { observed += it }
        }
        runCurrent()
        assertEquals(listOf(saved), observed.last())
        val stale = listOf(saved.copy(runId = "another-run"), saved.copy(fence = saved.fence + 1), saved.copy(retryAt = saved.retryAt + 1))
        for (candidate in stale) {
            payload.value = listOf(candidate)
            runCurrent()
            assertTrue(observed.last().isEmpty())
        }
        val replacement = saved.copy(fence = saved.fence + 1)
        payload.value = listOf(replacement)
        owners.value = listOf(owner(replacement))
        runCurrent()
        assertEquals(listOf(replacement), observed.last())
        assertEquals(2, subscriptions)
        owners.value = emptyList()
        runCurrent()
        payload.value = listOf(saved)
        runCurrent()
        assertTrue(observed.last().isEmpty())
        assertEquals(2, subscriptions)
    }
}
