package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPublicAuthCoordinatorTest {
    private class MemoryVault : SecretVault {
        private val values = ConcurrentHashMap<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)?.fill(0)
        }
    }

    @Test fun deviceAuthorizationWaitsForDurableRevocationBeforeActivatingAnyIdentity() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val durable = CompletableDeferred<Unit>()
            f.revokeGrants = {
                entered.complete(Unit)
                durable.await()
            }
            val coordinator = f.coordinator()
            val completing = async { coordinator.completeDevice(f.connection, f.config.clientId, GitHubOAuthCredential("ghu_first")) }
            entered.await()
            assertEquals(null, f.connection.secretRef)
            assertEquals(null, f.vault.read("stored-access"))
            assertEquals(null, f.vault.read(GitHubPublicAuthCoordinator.bindingReference("github")))
            assertEquals(null, coordinator.session("github"))
            durable.complete(Unit)
            assertEquals("1", completing.await().id)
            assertEquals(1, f.revocations)
            assertEquals("ghu_first", f.vault.read("stored-access")?.decodeToString())
        }
    }

    @Test fun browserAndResumedAuthorizationUseTheSameDurableActivationFence() = runBlocking {
        for (resume in listOf(false, true)) {
            Fixture().use { f ->
                val coordinator = f.coordinator()
                coordinator.begin("github")
                if (resume) {
                    f.lostRedemptionResponse = true
                    assertTrue(runCatching { coordinator.complete(f.validCallback()) }.isFailure)
                    f.lostRedemptionResponse = false
                }
                val entered = CompletableDeferred<Unit>()
                val durable = CompletableDeferred<Unit>()
                f.revokeGrants = {
                    entered.complete(Unit)
                    durable.await()
                }
                val completing = async { if (resume) coordinator.resumePendingAuthorization() else coordinator.complete(f.validCallback()) }
                entered.await()
                assertEquals(null, f.connection.secretRef)
                assertEquals(null, f.vault.read(GitHubPublicAuthCoordinator.bindingReference("github")))
                assertEquals(null, coordinator.session("github"))
                durable.complete(Unit)
                assertEquals("1", completing.await().id)
                assertEquals(1, f.revocations)
            }
        }
    }

    @Test fun failedRevocationCannotActivateCredentialsAndPendingAuthorizationCanRetry() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            f.revokeGrants = { throw IOException("durable revocation failed") }
            assertTrue(runCatching { coordinator.complete(f.validCallback()) }.isFailure)
            assertEquals(null, f.connection.secretRef)
            assertEquals(null, f.vault.read("stored-access"))
            assertEquals(null, f.vault.read(GitHubPublicAuthCoordinator.bindingReference("github")))
            assertEquals(null, coordinator.session("github"))
            assertTrue(coordinator.canResumeAuthorization())
            f.revokeGrants = {}
            assertEquals("1", coordinator.resumePendingAuthorization().id)
            assertEquals(1, f.revocations)
        }
    }

    private class Fixture : AutoCloseable {
        val vault = MemoryVault()
        val repository = mockk<ToolConnectionRepository>()
        val origin = "https://auth.example.org"
        val callback = "$origin/oauth/github/complete"
        val state = "s".repeat(43)
        val config = GitHubPublicAuthConfiguration("Iv1.project", origin, callback)
        var connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", null, null)
        var now = 1_000_000L
        var exchanges = 0
        var rotations = 0
        var deviceRotations = 0
        var token = "ghu_first"
        var expiresIn = 28800
        var uncertainRefresh = false
        var lostRedemptionResponse = false
        var revocations = 0
        var revokeGrants: suspend () -> Unit = {}
        val http = HttpClient(
            MockEngine { request ->
                val response = when {
                    request.url.encodedPath.endsWith("/start") -> {
                        val verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
                        val params = mapOf("client_id" to config.clientId, "redirect_uri" to "$origin/oauth/github/callback", "state" to state, "code_challenge" to GitHubPublicAuthClient.challenge(verifier), "code_challenge_method" to "S256", "scope" to "offline_access")
                        buildJsonObject {
                            put("state", state)
                            put("expiresIn", 600)
                            put("redirectUri", "$origin/oauth/github/callback")
                            put("appLinkUri", callback)
                            put("protocolVersion", 2)
                            put("authorizationUrl", "https://github.com/login/oauth/authorize?" + params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") })
                        }.toString()
                    }
                    request.url.encodedPath.endsWith("/redeem") -> {
                        exchanges++
                        if (lostRedemptionResponse) throw IOException("Redemption response lost")
                        """{"access_token":"$token","refresh_token":"ghr_first","token_type":"bearer","expires_in":$expiresIn,"refresh_token_expires_in":15897600}"""
                    }
                    request.url.encodedPath.endsWith("/refresh") -> {
                        rotations++
                        if (uncertainRefresh) throw IOException("Remote outcome unknown")
                        """{"access_token":"ghu_rotated","refresh_token":"ghr_rotated","token_type":"bearer","expires_in":28800,"refresh_token_expires_in":15897600}"""
                    }
                    request.url.toString() == GitHubDeviceAuthClient.TOKEN_ENDPOINT -> {
                        deviceRotations++
                        if (uncertainRefresh) throw IOException("Remote outcome unknown")
                        """{"access_token":"ghu_rotated","refresh_token":"ghr_rotated","token_type":"bearer","expires_in":28800,"refresh_token_expires_in":15897600}"""
                    }
                    else -> error("Unexpected authorization endpoint")
                }
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )

        init {
            coEvery { repository.getConnection("github") } answers { connection }
            coEvery { repository.upsertConnection(any(), any(), any()) } coAnswers {
                if (thirdArg<Boolean>()) {
                    connection = firstArg<ToolConnection>().copy(secretRef = null)
                    vault.delete("stored-access")
                } else {
                    connection = firstArg<ToolConnection>().copy(secretRef = "stored-access")
                    vault.put("stored-access", secondArg<ByteArray?>()!!)
                }
            }
        }

        fun coordinator(configuration: GitHubPublicAuthConfiguration = config) = GitHubPublicAuthCoordinator(repository, vault, configuration, { now }, { GitHubPublicAuthClient(http, origin, callback, config.clientId, { now }, { ByteArray(it) }) }, { access ->
            GitHubPublicAuthCoordinator.Account(if (access == "ghu_second_account") "2" else "1", "coder")
        }, { clientId, refreshToken -> GitHubDeviceAuthClient(http, wallMillis = { now }).refresh(clientId, refreshToken) }, { uid, activate ->
            assertEquals("github", uid)
            revokeGrants()
            revocations++
            activate()
        })
        fun validCallback() = "$callback?state=$state&ticket=${"t".repeat(43)}"
        override fun close() {
            http.close()
            GitHubSessionCaches.credentialReconnected("ghu_first")
        }
    }

    @Test fun anOpenedWorkspaceUsesTheCurrentSecretReferenceAfterRotation() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.completeDevice(f.connection, f.config.clientId, GitHubOAuthCredential("ghu_first", "ghr_first", f.now + 1000, f.now + 100_000))
            val opened = f.connection
            val originalIdentity = coordinator.durableIdentity(opened.connectionUid)
            f.now += 2000
            assertEquals("ghu_rotated", coordinator.accessToken(opened))
            assertEquals(1, f.revocations)
            assertEquals(originalIdentity, coordinator.durableIdentity(opened.connectionUid))
            f.vault.put("rotated-reference", "ghu_rotated".encodeToByteArray())
            f.vault.delete("stored-access")
            f.connection = f.connection.copy(secretRef = "rotated-reference", updatedAt = f.connection.updatedAt + 1)
            assertEquals("ghu_rotated", coordinator.accessToken(opened))
            assertEquals(originalIdentity, coordinator.durableIdentity(opened.connectionUid))
            assertEquals(1, f.deviceRotations)
        }
    }

    @Test fun deviceCredentialsUseTheSameDurableRefreshAndAccountBoundary() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.completeDevice(f.connection, f.config.clientId, GitHubOAuthCredential("ghu_first", "ghr_first", f.now + 1000, f.now + 100_000))
            assertEquals("1", coordinator.session("github")?.account?.id)
            assertEquals(f.now + 1000, coordinator.session("github")?.expiresAt)
            f.now += 2000
            assertEquals("ghu_rotated", coordinator.accessToken(f.connection))
            assertEquals(0, f.exchanges)
            assertEquals(0, f.rotations)
            assertEquals(1, f.deviceRotations)
            assertEquals("DEVICE", coordinator.session("github")?.refreshMethod)
        }
    }

    @Test fun aCustomDeviceAppRefreshesWithoutTheProjectsConfidentialBroker() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator(GitHubPublicAuthConfiguration("", "", f.callback))
            coordinator.completeDevice(f.connection, "Iv1.custom", GitHubOAuthCredential("ghu_first", "ghr_first", f.now + 1000, f.now + 100_000))
            assertEquals("Iv1.custom", coordinator.session("github")?.clientId)
            f.now += 2000
            assertEquals("ghu_rotated", coordinator.accessToken(f.connection))
            assertEquals("Iv1.custom", coordinator.session("github")?.clientId)
            assertEquals(0, f.rotations)
            assertEquals(1, f.deviceRotations)
        }
    }

    @Test fun uncertainDeviceRotationIsNotReplayedAfterProcessRestart() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.completeDevice(f.connection, "Iv1.custom", GitHubOAuthCredential("ghu_first", "ghr_first", f.now + 1000, f.now + 100_000))
            f.now += 2000
            f.uncertainRefresh = true
            assertTrue(runCatching { coordinator.accessToken(f.connection) }.isFailure)
            assertEquals("REAUTH_REQUIRED", coordinator.session("github")?.refreshState)
            assertTrue(runCatching { f.coordinator().accessToken(f.connection) }.isFailure)
            assertEquals(1, f.deviceRotations)
            assertEquals(0, f.rotations)
        }
    }

    @Test fun changingTheRegisteredClientCannotReuseAnExistingDeviceSession() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.completeDevice(f.connection, "Iv1.custom", GitHubOAuthCredential("ghu_first", "ghr_first", f.now + 100_000, f.now + 200_000))
            f.connection = f.connection.copy(oauthClientId = "Iv1.different")
            assertTrue(runCatching { f.coordinator().accessToken(f.connection) }.exceptionOrNull()?.message.orEmpty().contains("configuration changed"))
            assertEquals(0, f.rotations)
            assertEquals(0, f.deviceRotations)
            assertEquals("ghu_first", f.vault.read("stored-access")!!.decodeToString())
        }
    }

    @Test fun restoredSessionChecksAccountBeforeReturningAccessAndRetainsRefreshExpiry() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            val original = requireNotNull(coordinator.session("github"))
            assertTrue(requireNotNull(original.refreshExpiresAt) > f.now)
            fun encoded(value: GitHubPublicAuthCoordinator.Session) = kotlinx.serialization.json.Json.encodeToString(GitHubPublicAuthCoordinator.Session.serializer(), value).toByteArray()
            f.vault.put(GitHubPublicAuthCoordinator.sessionReference("github"), encoded(original.copy(verifyAfterRestore = true, account = original.account.copy(id = "2"))))
            assertTrue(runCatching { coordinator.accessToken(f.connection) }.isFailure)
            assertEquals(0, f.rotations)
            f.vault.put(GitHubPublicAuthCoordinator.sessionReference("github"), encoded(original.copy(verifyAfterRestore = true)))
            assertEquals("ghu_first", coordinator.accessToken(f.connection))
            assertEquals(false, coordinator.session("github")?.verifyAfterRestore)
        }
    }

    @Test fun expiredRestoredSessionRotatesOnceAndVerifiesTheNewCredential() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            val original = requireNotNull(coordinator.session("github"))
            val encoded = kotlinx.serialization.json.Json.encodeToString(GitHubPublicAuthCoordinator.Session.serializer(), original.copy(verifyAfterRestore = true, expiresAt = f.now)).toByteArray()
            f.vault.put(GitHubPublicAuthCoordinator.sessionReference("github"), encoded)
            assertEquals("ghu_rotated", coordinator.accessToken(f.connection))
            assertEquals(1, f.rotations)
            assertEquals(false, coordinator.session("github")?.verifyAfterRestore)
        }
    }

    @Test fun processRestartCanCompletePendingSignInExactlyOnce() = runBlocking {
        Fixture().use { f ->
            f.coordinator().begin("github")
            assertTrue(f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE) != null)
            // Database audit timestamps may advance while the durable authorization is pending.
            f.connection = f.connection.copy(createdAt = f.connection.createdAt + 1, updatedAt = f.connection.updatedAt + 1)
            val restarted = f.coordinator()
            assertEquals("1", restarted.complete(f.validCallback()).id)
            assertEquals(null, f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE))
            assertTrue(runCatching { f.coordinator().complete(f.validCallback()) }.isFailure)
            assertEquals(1, f.exchanges)
            assertEquals("ghu_first", restarted.accessToken(f.connection))
            assertFalse(restarted.session("github").toString().contains("ghr_first"))
        }
    }

    @Test fun lostTicketResponseSurvivesRestartAndResumesTheSameBinding() = runBlocking {
        Fixture().use { f ->
            val first = f.coordinator()
            first.begin("github")
            f.lostRedemptionResponse = true
            assertTrue(runCatching { first.complete(f.validCallback()) }.isFailure)
            assertTrue(first.canResumeAuthorization())
            val pending = f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE)!!
            assertFalse(pending.decodeToString().contains("private-code"))
            f.lostRedemptionResponse = false
            val restarted = f.coordinator()
            assertTrue(restarted.canResumeAuthorization())
            assertEquals("1", restarted.resumePendingAuthorization().id)
            assertFalse(restarted.canResumeAuthorization())
            assertEquals(2, f.exchanges) // two ticket requests; broker test proves one GitHub exchange
        }
    }

    @Test fun wrongStateCannotConsumeThePendingTransactionAndDenialDoesNotExchange() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            assertTrue(runCatching { coordinator.complete("${f.callback}?state=wrong&ticket=${"t".repeat(43)}") }.isFailure)
            assertTrue(f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE) != null)
            assertTrue(runCatching { coordinator.complete("${f.callback}?state=${f.state}&error=authorization_declined") }.isFailure)
            assertEquals(null, f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE))
            assertEquals(0, f.exchanges)
        }
    }

    @Test fun uncertainRefreshIsNotReplayedAfterRestart() = runBlocking {
        Fixture().use { f ->
            f.expiresIn = 1
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            f.now += 2000
            f.uncertainRefresh = true
            assertTrue(runCatching { coordinator.accessToken(f.connection) }.isFailure)
            assertEquals("REAUTH_REQUIRED", coordinator.session("github")?.refreshState)
            assertTrue(runCatching { f.coordinator().accessToken(f.connection) }.isFailure)
            assertEquals(1, f.rotations)
            assertEquals("ghu_first", f.vault.read("stored-access")!!.decodeToString())
        }
    }

    @Test fun rejectedAccessTokenRotatesEvenBeforeTheDeviceClockPredictsExpiration() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            GitHubSessionCaches.authenticationForCredential("ghu_first").set(true)
            assertEquals("ghu_rotated", coordinator.accessToken(f.connection))
            assertEquals(1, f.rotations)
        }
    }

    @Test fun concurrentConsumersShareOneVerifiedRotation() = runBlocking {
        Fixture().use { f ->
            f.expiresIn = 1
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            f.now += 2000
            val tokens = (1..8).map { async { coordinator.accessToken(f.connection) } }.awaitAll()
            assertEquals(List(8) { "ghu_rotated" }, tokens)
            assertEquals(1, f.rotations)
            assertEquals("READY", coordinator.session("github")?.refreshState)
            assertEquals("ghu_rotated", f.vault.read("stored-access")!!.decodeToString())
        }
    }

    @Test fun pendingSignInCannotBeOverwrittenAndCanBeExplicitlyCancelled() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            val before = f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE)!!
            assertTrue(runCatching { coordinator.begin("github") }.isFailure)
            assertTrue(before.contentEquals(f.vault.read(GitHubPublicAuthCoordinator.PENDING_REFERENCE)))
            coordinator.cancel()
            assertEquals(null, coordinator.pendingConnectionUid())
            coordinator.begin("github")
            assertEquals("github", coordinator.pendingConnectionUid())
        }
    }

    @Test fun disconnectRemovesAccessRefreshAndPendingCredentialsWithoutAWrite() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            coordinator.begin("github")
            coordinator.disconnect("github")
            assertEquals(null, f.connection.secretRef)
            assertEquals(null, f.vault.read("stored-access"))
            assertEquals(null, coordinator.session("github"))
            assertEquals(null, coordinator.pendingConnectionUid())
            assertTrue(runCatching { coordinator.accessToken(f.connection) }.isFailure)
            assertEquals(0, f.rotations)
            f.token = "ghu_second_account"
            coordinator.begin("github")
            assertTrue("Signing out cannot silently rebind the same workspace to another account", runCatching { coordinator.complete(f.validCallback()) }.isFailure)
        }
    }

    @Test fun reconnectCannotReplaceAnotherAccountsCredential() = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.begin("github")
            coordinator.complete(f.validCallback())
            f.token = "ghu_second_account"
            coordinator.begin("github")
            assertTrue(runCatching { coordinator.complete(f.validCallback()) }.isFailure)
            assertEquals("1", coordinator.session("github")?.account?.id)
            assertEquals("ghu_first", f.vault.read("stored-access")!!.decodeToString())
        }
    }
}
