package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import dev.chungjungsoo.gptmobile.data.network.FreeCooldown
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.freeCredentialIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWaitScheduleTest {
    private val provider = FreeAiProvider.LLM7
    private val profile = PlatformV2(uid = "limited", name = "Limited", apiUrl = provider.apiUrl, model = "model", token = "credential-b", compatibleType = ClientType.FREE)
    private val codec = Json { encodeDefaults = true }

    private fun row(retryAt: Long = 30_156, scope: String = "free-quota:saved", admitted: PlatformV2 = profile, captured: PlatformV2 = admitted): ProviderWait {
        val revision = ConfiguredDispatchAuthority.profileRevision(admitted)
        val authority = CapturedProviderAuthority(
            admitted.uid,
            admitted.model,
            ConfiguredDispatchAuthority.profileRevision(captured),
            "policy",
            "permission",
            CapturedProviderAuthority.routeRevision(admitted),
            acceptedProfileOutputLimit = captured.maxTokens,
            profileWithoutOutputLimitRevision = ConfiguredDispatchAuthority.profileWithoutOutputLimitRevision(captured)
        )
        val decision = ProviderLimitDecision(provider.name, admitted.uid, admitted.model, retryAt, scope, "PROVIDER_RATE_LIMITED", true, authority = authority)
        val saved = ProviderWaitCheckpoint(
            chatId = 1, platform = admitted.copy(token = null), registeredRevision = revision, policyRevision = "policy", permissionRevision = "permission",
            users = listOf(1), replies = listOf(emptyList()), inputDigest = "accepted-input", tools = null, stage = null, participants = listOf(admitted.uid),
            decision = decision, limitPlatform = admitted.copy(token = null), limitRegisteredRevision = revision, limitPolicyRevision = "policy", limitPermissionRevision = "permission"
        )
        return ProviderWait("run", 7, scope, retryAt, codec.encodeToString(saved))
    }

    @Test fun aPaidRevisionUpdatesTheLiveScheduleWithoutChangingTheSavedWait() = runTest {
        val saved = row(scope = "provider:account")
        val rows = MutableStateFlow(listOf(saved))
        val paid = MutableStateFlow(0L)
        val free = MutableStateFlow(0L)
        var shared: Long? = null
        val observed = mutableListOf<List<ProviderWaitDeadline>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rows.withCurrentProviderWaitSchedules(
                loadProfiles = { error("Paid scopes need no credential lookup") },
                paidRevisions = paid,
                freeRevisions = free,
                paidDeadline = {
                    assertEquals(saved.scope, it)
                    shared
                },
                freeDeadline = { error("Not a Free wait") }
            ).collect { observed += it }
        }
        runCurrent()
        assertEquals(saved.retryAt, observed.last().single().effectiveRetryAt)
        shared = 120_000
        paid.value++
        runCurrent()
        assertEquals(120_000L, observed.last().single().effectiveRetryAt)
        assertEquals(saved.retryAt, observed.last().single().retryAt)
        val count = observed.size
        paid.value++
        free.value++
        runCurrent()
        assertEquals(count, observed.size)
        shared = null
        paid.value++
        runCurrent()
        assertNull(observed.last().single().scheduledRetryAt)
        assertEquals(listOf(saved), rows.value)
        assertEquals(saved.retryAt, codec.decodeFromString<ProviderWaitCheckpoint>(saved.checkpoint).decision?.retryAt)
    }

    @Test fun aLaterFreeModelLimitExtendsTheOriginalCredentialWaitUsingItsBoundModel() {
        var wall = 1_000_000L
        var elapsed = 5000L
        val limiter = FreeAiRequestLimiter(elapsedMillis = { elapsed }, nowMillis = { wall })
        val identity = freeCredentialIdentity(provider, profile.token)
        limiter.defer(provider, "30", profile.model, identity, FreeCooldown(0, quotaScope = "token"))
        val saved = row(wall + 30_000, limiter.activeQuotaScopeKey(provider, profile.model, identity))
        val prepared = ProviderWaitSchedule.prepare(saved, listOf(profile))
        fun project() = prepared.project(paidDeadline = { null }) { selected ->
            limiter.cooldownUntilMillis(provider, selected.model, freeCredentialIdentity(provider, selected.token))
        }
        assertEquals(saved.retryAt, project().effectiveRetryAt)
        limiter.defer(provider, "120", profile.model, "other-credential", FreeCooldown(0, quotaScope = "model"))
        assertFalse(saved.scope == limiter.activeQuotaScopeKey(provider, profile.model, identity))
        assertEquals(wall + 120_000, project().effectiveRetryAt)
        assertEquals(saved.retryAt, project().retryAt)
        wall += 86_400_000
        assertEquals(wall + 120_000, project().effectiveRetryAt)
        elapsed += 120_000
        assertNull(project().scheduledRetryAt)
        assertEquals(saved.retryAt, codec.decodeFromString<ProviderWaitCheckpoint>(saved.checkpoint).decision?.retryAt)
    }

    @Test fun aBroadOriginNeverSubstitutesTheOtherCredentialsLaterCooldown() {
        for (origin in listOf("ip", "model")) {
            val now = 1_000_000L
            val limiter = FreeAiRequestLimiter { now }
            val other = profile.copy(uid = "other", token = "credential-a")
            val identity = freeCredentialIdentity(provider, profile.token)
            val otherIdentity = freeCredentialIdentity(provider, other.token)
            limiter.defer(provider, "30", profile.model, otherIdentity, FreeCooldown(0, quotaScope = origin))
            val saved = row(now + 30_000, limiter.activeQuotaScopeKey(provider, profile.model, identity))
            limiter.defer(provider, "120", profile.model, otherIdentity, FreeCooldown(0, quotaScope = "token"))
            limiter.defer(provider, "60", profile.model, identity, FreeCooldown(0, quotaScope = "token"))
            val projected = ProviderWaitSchedule.prepare(saved, listOf(profile, other)).project(paidDeadline = { null }) { selected ->
                assertEquals(profile.token, selected.token)
                limiter.cooldownUntilMillis(provider, selected.model, freeCredentialIdentity(provider, selected.token))
            }
            assertEquals(now + 60_000, projected.effectiveRetryAt)
            assertEquals(saved.retryAt, projected.retryAt)
            assertFalse(projected.toString().contains("credential-"))
        }
    }

    @Test fun aChangedCredentialOrCheckpointBindingCannotSupplyNewSchedulingAuthority() {
        val saved = row()
        val changed = profile.copy(token = "replacement-token")
        val invalid = listOf(
            saved to listOf(changed),
            saved.copy(scope = "free-quota:other") to listOf(profile),
            saved.copy(retryAt = saved.retryAt + 1) to listOf(profile),
            saved.copy(checkpoint = "unreadable") to listOf(profile),
            saved to emptyList()
        )
        invalid.forEach { (candidate, current) ->
            val projected = ProviderWaitSchedule.prepare(candidate, current).project(paidDeadline = { null }) { error("Unbound credential must not be consulted") }
            assertEquals(candidate.retryAt, projected.effectiveRetryAt)
            assertNull(projected.scheduledRetryAt)
        }
    }

    @Test fun anAcceptedOutputIncreaseKeepsTheSavedCurrentProfileBinding() {
        val prior = profile.copy(maxTokens = 1000)
        val increased = profile.copy(maxTokens = 2000)
        val saved = row(admitted = increased, captured = prior)
        val projected = ProviderWaitSchedule.prepare(saved, listOf(increased)).project(paidDeadline = { null }) { selected ->
            assertEquals(increased.token, selected.token)
            assertEquals(increased.maxTokens, selected.maxTokens)
            120_000
        }
        assertEquals(120_000L, projected.effectiveRetryAt)
        assertEquals(saved.retryAt, projected.retryAt)
    }

    @Test fun replacingAWaitRevokesItsRevisionObserverWhileTheNewLookupIsSuspended() = runTest {
        val first = row()
        val replacement = row(retryAt = 50_000)
        val rows = MutableStateFlow(listOf(first))
        val paid = MutableStateFlow(0L)
        val free = MutableStateFlow(0L)
        val release = CompletableDeferred<Unit>()
        var lookups = 0
        var deadline = 90_000L
        val observed = mutableListOf<List<ProviderWaitDeadline>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rows.withCurrentProviderWaitSchedules(
                loadProfiles = {
                    if (++lookups == 2) release.await()
                    listOf(profile)
                },
                paidRevisions = paid,
                freeRevisions = free,
                paidDeadline = { null },
                freeDeadline = { deadline }
            ).collect { observed += it }
        }
        runCurrent()
        assertEquals(first.retryAt, observed.last().single().retryAt)
        rows.value = listOf(replacement)
        runCurrent()
        assertTrue(observed.last().isEmpty())
        val afterReplacement = observed.size
        deadline = 150_000
        paid.value++
        free.value++
        runCurrent()
        assertEquals(afterReplacement, observed.size)
        release.complete(Unit)
        runCurrent()
        assertEquals(replacement.retryAt, observed.last().single().retryAt)
        assertEquals(deadline, observed.last().single().effectiveRetryAt)
        assertEquals(2, lookups)
    }

    @Test fun removingTheCurrentOwnerCancelsItsLookupAndCannotRepublishItsDeadline() = runTest {
        val saved = row()
        val owners = MutableStateFlow(listOf(ProviderWaitDeadline(saved.runId, saved.fence, saved.retryAt)))
        val rows = MutableStateFlow(listOf(saved))
        val paid = MutableStateFlow(0L)
        val free = MutableStateFlow(0L)
        var cancelled = false
        val observed = mutableListOf<List<ProviderWaitDeadline>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owners.withCurrentProviderWaitRows { rows }.withCurrentProviderWaitSchedules(
                loadProfiles = {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
                paidRevisions = paid,
                freeRevisions = free,
                paidDeadline = { error("No current owner") },
                freeDeadline = { error("No current owner") }
            ).collect { observed += it }
        }
        runCurrent()
        owners.value = emptyList()
        runCurrent()
        assertTrue(cancelled)
        paid.value++
        free.value++
        runCurrent()
        assertTrue(observed.all { it.isEmpty() })
    }
}
