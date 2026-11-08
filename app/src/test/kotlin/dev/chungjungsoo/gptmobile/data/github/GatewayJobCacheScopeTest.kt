package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.dao.GatewayJobDao
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJobCacheScopeTest {
    @Test fun cachedHistoryQueriesOnlyTheVerifiedOriginalConnectionAndAccount() = runTest {
        val fixture = Fixture()

        val cached = requireNotNull(fixture.repository.cachedConversations("profile"))

        assertEquals("installation", cached.page.gatewayId)
        assertEquals("account", cached.page.principalId)
        assertEquals(listOf(fixture.snapshot), cached.page.items)
        assertEquals(5000L, cached.lastSyncedAtMillis)
        coVerify(exactly = 1) { fixture.dao.cachedConversations("profile", fixture.endpoint, "installation", "account") }
        fixture.verifyNoRemoteCall()
    }

    @Test fun unknownConfigurationOrUnverifiedAccountNeverQueriesSavedConversations() = runTest {
        for (missingConfiguration in listOf(true, false)) {
            val fixture = Fixture()
            if (missingConfiguration) fixture.revision = null else fixture.scope = null

            assertNull(fixture.repository.cachedConversations("profile"))

            coVerify(exactly = 0) { fixture.dao.cachedConversations(any(), any(), any(), any()) }
            fixture.verifyNoRemoteCall()
        }
    }

    @Test fun serializedSnapshotsCannotSubstituteAnotherGatewayPrincipalOrConversation() = runTest {
        val fixture = Fixture()
        coEvery { fixture.dao.cachedConversations(any(), any(), any(), any()) } returns listOf(
            fixture.record,
            fixture.record.copy(snapshot = Json.encodeToString(fixture.snapshot.copy(gatewayId = "other-installation"))),
            fixture.record.copy(snapshot = Json.encodeToString(fixture.snapshot.copy(principalId = "other-account"))),
            fixture.record.copy(snapshot = Json.encodeToString(fixture.snapshot.copy(conversationId = "other-conversation"))),
            fixture.record.copy(snapshot = "not-json")
        )

        assertEquals(listOf(fixture.snapshot), fixture.repository.cachedConversations("profile")?.page?.items)
        fixture.verifyNoRemoteCall()
    }

    @Test fun endpointTokenOrOpaqueCredentialRevisionChangesDiscardTheSuspendedCacheRead() = runTest {
        for (change in 0..2) {
            val fixture = Fixture()
            coEvery { fixture.dao.cachedConversations(any(), any(), any(), any()) } answers {
                when (change) {
                    0 -> fixture.profile = fixture.profile.copy(apiUrl = "http://192.168.1.10:8090")
                    1 -> fixture.profile = fixture.profile.copy(token = "replacement-credential")
                    else -> fixture.revision = "b".repeat(64)
                }
                listOf(fixture.record)
            }

            val result = runCatching { fixture.repository.cachedConversations("profile") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("changed"))
            fixture.verifyNoRemoteCall()
        }
    }

    @Test fun newlyVerifiedAccountDuringTheCacheReadCannotPublishThePreviousAccountPage() = runTest {
        val fixture = Fixture()
        coEvery { fixture.dao.cachedConversations(any(), any(), any(), any()) } answers {
            fixture.scope = requireNotNull(fixture.scope).copy(gatewayId = "replacement-installation", principalId = "replacement-account")
            listOf(fixture.record)
        }

        val result = runCatching { fixture.repository.cachedConversations("profile") }

        assertTrue(result.isFailure)
        fixture.verifyNoRemoteCall()
    }

    private class Fixture {
        val endpoint = "http://192.168.1.9:8090"
        var profile = PlatformV2(uid = "profile", name = "Original profile", compatibleType = ClientType.LLAMA, apiUrl = endpoint, token = "original-credential", secretRef = "credential", model = "model")
        var revision: String? = "a".repeat(64)
        var scope: GatewayVerifiedScope? = GatewayVerifiedScope(requireNotNull(revision), "installation", "account", 1000)
        val snapshot = GatewayConversationSnapshot(gatewayId = "installation", principalId = "account", conversationId = "conversation", profileId = "profile", model = "model")
        val record = GatewayConversationRecord(gatewayIdentityKey("installation", "account", "conversation"), "installation", "account", "conversation", "profile", endpoint, "profile", null, Json.encodeToString(snapshot), syncedAtMillis = 5000)
        val database = mockk<ChatDatabaseV2>()
        val dao = mockk<GatewayJobDao>()
        val settings = mockk<SettingRepository>()
        val client = mockk<GatewayJobClient>()
        val engineering = mockk<GatewayEngineeringStore>()
        val scopes = mockk<GatewayVerifiedScopeStore>()
        val repository = GatewayJobRepository(database, settings, client, engineering, scopes)

        init {
            every { database.gatewayJobDao() } returns dao
            coEvery { settings.fetchPlatformV2s() } answers { listOf(this@Fixture.profile) }
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { scopes.configurationRevision(any()) } answers { this@Fixture.revision }
            coEvery { scopes.read("profile", any()) } answers {
                this@Fixture.scope?.takeIf { it.configurationRevision == secondArg<String?>() }
            }
            coEvery { dao.cachedConversations("profile", endpoint, "installation", "account") } returns listOf(record)
        }

        fun verifyNoRemoteCall() {
            verify { client wasNot Called }
            verify { engineering wasNot Called }
        }
    }
}
