package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCooldownPreflightTest {
    private fun profile(): PlatformV2 = PlatformV2(uid = "p-${UUID.randomUUID()}", name = "Paid provider", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1", model = "selected", token = "test-${UUID.randomUUID()}")

    @Test fun sharedPaidCooldownIsKnownBeforePreparationAndDoesNotClaimAnotherHttpRejection() = runBlocking {
        val profile = profile()
        val config = ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid)
        withContext(ProviderWaitContext(durableRateLimits = true) {}) {
            val failure = ProviderCooldown.rejected(config, 429, "60", knownNotAccepted = true)
            val preflight = requireNotNull(providerPreflightLimit(profile.copy(uid = "other", model = "other-model")))
            assertEquals(ProviderCooldown.scope(config), preflight.scope)
            assertEquals("other", preflight.profileUid)
            assertEquals("other-model", preflight.model)
            assertEquals("LOCAL_COOLDOWN_ACTIVE", preflight.category)
            assertEquals("SHARED_COOLDOWN", preflight.retrySource)
            assertEquals(1, preflight.consecutiveRejections)
            assertTrue(preflight.knownNotAccepted)
            assertNull(preflight.httpStatus)
            assertTrue(kotlin.math.abs(requireNotNull(failure.retryAtMillis) - preflight.retryAt) < 1000)
            assertFalse(preflight.scope.contains(requireNotNull(profile.token)))
            assertNull(providerPreflightLimit(profile.copy(token = "different-${UUID.randomUUID()}")))
        }
    }

    @Test fun anAvailableAlternateCredentialRemainsDispatchableAndAllBlockedKeysUseTheEarliestDeadline() {
        val profile = profile()
        val alternate = "alternate-${UUID.randomUUID()}"
        ProviderCooldown.defer(ProviderRequestConfig(profile.apiUrl, profile.token), "120")
        val multiple = profile.copy(token = "${profile.token}\n$alternate")
        assertNull(ProviderCooldown.preflight(multiple))
        ProviderCooldown.defer(ProviderRequestConfig(profile.apiUrl, alternate), "60")
        val result = requireNotNull(ProviderCooldown.preflight(multiple))
        assertEquals(ProviderCooldown.scope(ProviderRequestConfig(profile.apiUrl, alternate)), result.scope)
        assertTrue(result.retryAt - System.currentTimeMillis() in 50_000..61_000)
    }

    @Test fun repeatedAdmissionIsReadOnlyAndDoesNotRotateCredentialsOrExtendTheDeadline() {
        val profile = profile()
        val alternate = "alternate-${UUID.randomUUID()}"
        val multiple = profile.copy(token = "${profile.token}\n$alternate")
        ProviderCooldown.defer(ProviderRequestConfig(profile.apiUrl, profile.token), "60")
        ProviderCooldown.defer(ProviderRequestConfig(profile.apiUrl, alternate), "120")
        val first = requireNotNull(ProviderCooldown.preflight(multiple))
        repeat(2) {
            val next = requireNotNull(ProviderCooldown.preflight(multiple))
            assertEquals(first.scope, next.scope)
            assertTrue(kotlin.math.abs(first.retryAt - next.retryAt) < 1000)
        }
        assertEquals(listOf(profile.token, alternate), ApiCredentialRotator.keysForNewRequest(profile.uid, multiple.token))
    }
}
