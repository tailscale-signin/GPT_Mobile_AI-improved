package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredDispatchAuthorityCompositionTest {
    @Test fun parentPolicyChangedDuringSuspendedChildSettingsStopsPhysicalWork() = runTest {
        val fixture = Fixture()
        val parentAuthority = requireNotNull(ConfiguredDispatchAuthority.capture(fixture.settings, fixture.profile).authority)
        var revision = 1
        val revoked = DispatchPolicyChangedException(listOf("devicePrivacy"))
        val parent = RequestAuthorizationGuard("captured-parent", parentAuthority) {
            if (revision != 1) throw revoked
        }
        val guard = withContext(parent) { ConfiguredDispatchAuthority.capture(fixture.settings, fixture.profile) }
        assertEquals(parentAuthority, requireNotNull(guard.authority).parents.single())
        val readsBefore = fixture.featureReads
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.onFeatureRead = {
            entered.complete(Unit)
            resume.await()
        }
        var physicalCalls = 0
        supervisorScope {
            val pending = async {
                withContext(guard) {
                    guard.validate()
                    physicalCalls++
                }
            }
            entered.await()
            revision++
            revision++
            resume.complete(Unit)
            assertSame(revoked, runCatching { pending.await() }.exceptionOrNull())
        }
        assertEquals(0, physicalCalls)
        assertEquals(readsBefore + 1, fixture.featureReads)
    }

    @Test fun revokedParentDuringCaptureCannotBecomeAChildConfigurationRetry() = runTest {
        val fixture = Fixture()
        var revision = 1
        val revoked = DispatchPolicyChangedException(listOf("devicePrivacy"))
        val parent = RequestAuthorizationGuard("captured-parent") {
            if (revision != 1) throw revoked
        }
        fixture.onFeatureRead = {
            if (fixture.featureReads == 2) revision = 2
        }
        val failure = withContext(parent) {
            runCatching { ConfiguredDispatchAuthority.capture(fixture.settings, fixture.profile) }.exceptionOrNull()
        }
        assertSame(revoked, failure)
        assertEquals("A revoked parent cannot be resnapshotted as child configuration", 2, fixture.featureReads)
    }

    @Test fun cancelledChildSettingsReadRetainsItsOriginalCancellationWithoutRetry() = runTest {
        val fixture = Fixture()
        val cancelled = CancellationException("Configuration read cancelled")
        fixture.onFeatureRead = {
            if (fixture.featureReads == 2) throw cancelled
        }
        val failure = withContext(RequestAuthorizationGuard("valid-parent") {}) {
            runCatching { ConfiguredDispatchAuthority.capture(fixture.settings, fixture.profile) }.exceptionOrNull()
        }
        assertSame(cancelled, failure)
        assertEquals(2, fixture.featureReads)
    }

    @Test fun childOnlyConfigurationRetriesKeepTheirExistingThreeAttemptBound() = runTest {
        for (settles in listOf(true, false)) {
            val fixture = Fixture()
            var enabled = true
            fixture.onFeatureRead = {
                if (fixture.featureReads % 2 == 0 && (!settles || fixture.featureReads == 2)) {
                    enabled = !enabled
                    fixture.features = fixture.features.copy(toolPluginStates = mapOf(ToolPluginId.CALCULATOR to enabled))
                }
            }
            val result = withContext(RequestAuthorizationGuard("valid-parent") {}) {
                runCatching { ConfiguredDispatchAuthority.capture(fixture.settings, fixture.profile) }
            }
            if (settles) {
                assertTrue(result.toString(), result.isSuccess)
                assertEquals(4, fixture.featureReads)
            } else {
                assertTrue(result.exceptionOrNull() is DispatchPolicyChangedException)
                assertEquals(6, fixture.featureReads)
            }
        }
    }

    private class Fixture {
        val profile = PlatformV2(id = 7, uid = "configured", name = "Configured", model = "accepted-model")
        var features = AppFeatureSettings()
        var featureReads = 0
        var onFeatureRead: suspend () -> Unit = {}
        val settings = mockk<SettingRepository>().also { repository ->
            coEvery { repository.providerActivationRequired(any()) } returns false
            coEvery { repository.providerPermissionRevision(any()) } returns "accepted"
            coEvery { repository.fetchPlatformV2s() } returns listOf(profile)
            coEvery { repository.getFeatureSettings() } coAnswers {
                featureReads++
                onFeatureRead()
                features
            }
        }
    }
}
