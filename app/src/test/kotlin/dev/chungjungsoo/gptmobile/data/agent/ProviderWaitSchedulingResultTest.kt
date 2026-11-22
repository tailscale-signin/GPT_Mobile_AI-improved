package dev.chungjungsoo.gptmobile.data.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderWaitSchedulingResultTest {
    @Test fun lostCommitResponseUsesExactOwnedEvidenceWithoutRepeatingTheWrite() = runTest {
        for (owned in listOf(true, false, null)) {
            var writes = 0
            var scheduled = 0
            val failure = IOException("private database detail")
            val result = persistProviderWaitSafely(
                persist = {
                    writes++
                    throw failure
                },
                observeOwned = { owned },
                enqueue = { scheduled++ }
            )
            assertEquals(1, writes)
            assertEquals(if (owned == true) 1 else 0, scheduled)
            assertEquals(owned, result.persisted)
            assertEquals(owned == true, result.waiting)
            assertEquals(
                when (owned) {
                    true -> null
                    false -> ProviderWaitSchedulingResult.NoWaitReason.PERSISTENCE_FAILED
                    null -> ProviderWaitSchedulingResult.NoWaitReason.PERSISTENCE_OUTCOME_UNKNOWN
                },
                result.noWaitReason
            )
            assertFalse(result.fields().toString().contains("private"))
            if (owned == null) assertFalse("provider_wait_persisted" in result.fields())
            if (owned == true) assertTrue(result.valueOrThrow()) else assertSame(failure, runCatching { result.valueOrThrow() }.exceptionOrNull())
        }
    }

    @Test fun failedPersistenceReadRetainsUnknownOutcomeAndForbidsReplay() = runTest {
        val original = ProfileFailureRecord("RATE_LIMIT", true, requestReplayAllowed = true, recoveryDetails = buildJsonObject { put("checkpoint_digest", "a".repeat(64)) })
        var scheduled = 0
        val result = persistProviderWaitSafely(
            persist = { throw IOException("lost response") },
            observeOwned = { throw IOException("cannot read") },
            enqueue = { scheduled++ }
        )
        val retained = result.retain(original)
        assertEquals(0, scheduled)
        assertNull(result.persisted)
        assertEquals("RATE_LIMIT", retained.category)
        assertFalse(retained.retryAllowed)
        assertEquals(false, retained.requestReplayAllowed)
        assertEquals(JsonPrimitive("a".repeat(64)), retained.recoveryDetails?.get("checkpoint_digest"))
        assertEquals(JsonPrimitive("PERSISTENCE_OUTCOME_UNKNOWN"), retained.recoveryDetails?.get("provider_wait_no_wait_reason"))
    }

    @Test fun explicitNoWaitCannotKeepReplayPermissionFromTheEarlierProviderRejection() {
        val original = ProfileFailureRecord("RATE_LIMIT", true, requestReplayAllowed = true)
        for (reason in ProviderWaitSchedulingResult.NoWaitReason.entries) {
            val retained = ProviderWaitSchedulingResult.refused(reason).retain(original)
            assertEquals("RATE_LIMIT", retained.category)
            assertFalse(retained.retryAllowed)
            assertEquals(false, retained.requestReplayAllowed)
        }
        val stricter = original.copy(retryAllowed = false, requestReplayAllowed = false)
        val waiting = ProviderWaitSchedulingResult.durable(true).retain(stricter)
        assertFalse(waiting.retryAllowed)
        assertEquals(false, waiting.requestReplayAllowed)
    }

    @Test fun enqueueFailureRetainsTheDurableOwnerForReconciliation() = runTest {
        var writes = 0
        var observations = 0
        var enqueues = 0
        val result = persistProviderWaitSafely(
            persist = {
                writes++
                true
            },
            observeOwned = {
                observations++
                false
            },
            enqueue = {
                enqueues++
                throw IOException("scheduler unavailable")
            }
        )
        assertEquals(1, writes)
        assertEquals(0, observations)
        assertEquals(1, enqueues)
        assertTrue(result.valueOrThrow())
        assertEquals("DURABLE_WAIT", result.decisionState)
        assertEquals("PENDING_RECONCILIATION", result.schedulerState)
        assertNull(result.noWaitReason)
        assertEquals(JsonPrimitive(true), result.fields()["provider_wait_persisted"])
    }

    @Test fun rejectedFenceNeverSchedulesOrReinterpretsAnotherOwnedWait() = runTest {
        var observations = 0
        var enqueues = 0
        val result = persistProviderWaitSafely(
            persist = { false },
            observeOwned = {
                observations++
                true
            },
            enqueue = { enqueues++ }
        )
        assertFalse(result.valueOrThrow())
        assertEquals(0, observations)
        assertEquals(0, enqueues)
        assertEquals(ProviderWaitSchedulingResult.NoWaitReason.PERSISTENCE_REJECTED, result.noWaitReason)
    }

    @Test fun cancellationNeverBecomesAPersistenceDecision() = runTest {
        val cancelled = CancellationException("cancelled")
        var observations = 0
        val caught = runCatching {
            persistProviderWaitSafely(
                persist = { throw cancelled },
                observeOwned = {
                    observations++
                    true
                },
                enqueue = { error("must not schedule") }
            )
        }.exceptionOrNull()
        assertSame(cancelled, caught)
        assertEquals(0, observations)
    }

    @Test fun typedNonQuotaCausesOverrideIncidentalHttp429() {
        for (category in listOf("BILLING_UNAVAILABLE", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "PROVIDER_CONTENT_POLICY", "TOOL_OUTCOME_UNKNOWN", GatewayOutcomeRecovery.ERROR_CODE)) {
            assertFalse(providerWaitDecisionRequired("429", ProfileFailureRecord(category, false)))
        }
        assertTrue(providerWaitDecisionRequired(null, ProfileFailureRecord("RATE_LIMIT", true)))
        assertTrue(providerWaitDecisionRequired("429", null))
    }
}
