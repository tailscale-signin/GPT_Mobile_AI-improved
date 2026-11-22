package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.PrivateExternalServiceGate
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneRepositoryGraphRepositoryTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
    private val profile = PlatformV2(uid = "cloud-ai", name = "Cloud AI", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "cloud-model")
    private var profiles = listOf(profile)
    private var features = AppFeatureSettings(githubProfileOverrides = mapOf(profile.uid to GitHubChatProfileOverride(engineeringMemory = GitHubEngineeringMemoryMode.SESSION_ONLY)))
    private val original = ToolConnection("github", "GitHub", "github", "GITHUB", null, "BEARER", "credential", null)
    private var connection: ToolConnection? = original
    private var account = "verified-account"
    private val settings = mockk<SettingRepository>()
    private val connections = mockk<ToolConnectionRepository>()
    private val authentication = mockk<GitHubPublicAuthCoordinator>()
    private val state = GitHubRepositoryStateStore()
    private val headers = headersOf("Content-Type", "application/json")
    private var requests = 0
    private var repositoryReads = 0
    private var privacyEnabled = false
    private var afterRepository: (() -> Unit)? = null
    private var afterTree: (() -> Unit)? = null
    private var repositoryStatus = HttpStatusCode.OK

    @Before fun initializePrivacyPolicy() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("privacy_mode_enabled", false) } answers { privacyEnabled }
        PrivacyModeStore.initialize(context)
    }

    @After fun clearPrivacyPolicy() {
        privacyEnabled = false
    }

    private fun http() = HttpClient(
        MockEngine { request ->
            requests++
            assertEquals("api.github.com", request.url.host)
            assertEquals("GET", request.method.value)
            when {
                request.url.encodedPath == "/repos/owner/repo" -> {
                    repositoryReads++
                    afterRepository?.invoke()
                    respond("""{"full_name":"owner/repo"}""", repositoryStatus, headers)
                }
                "/commits/" in request.url.encodedPath -> respond("""{"sha":"${context.commitSha}","commit":{"tree":{"sha":"${"b".repeat(40)}"}}}""", HttpStatusCode.OK, headers)
                "/git/trees/" in request.url.encodedPath -> {
                    afterTree?.invoke()
                    respond("""{"truncated":false,"tree":[{"path":"src/Example.kt","type":"blob","mode":"100644","sha":"${"c".repeat(40)}","size":20}]}""", HttpStatusCode.OK, headers)
                }
                else -> error("The phone map must never contact a Gateway or download source for status: ${request.url.encodedPath}")
            }
        }
    ) {
        install(HttpTimeout)
        install(PrivateExternalServiceGate)
    }

    private fun repository(http: HttpClient): PhoneRepositoryGraphRepository {
        coEvery { settings.fetchPlatformV2s() } coAnswers { profiles }
        coEvery { settings.getFeatureSettings() } coAnswers { features }
        coEvery { connections.getConnection("github") } coAnswers { connection }
        coEvery { authentication.accessToken(any()) } returns "test-only-token"
        coEvery { authentication.durableIdentity("github") } coAnswers { account }
        return PhoneRepositoryGraphRepository(settings, connections, authentication, state) { it.createClient(http) }
    }

    private fun action(name: String) = buildJsonObject { put("action", name) }

    @Test fun cloudProfileWithoutGatewayOrPersistentEngineeringMemoryGetsNativeMap() = runTest {
        http().use { transport ->
            val response = repository(transport).executeForMap(context, action("status"), forModel = true)
            assertEquals("", response.gatewayProfileUid)
            assertEquals("On this phone", response.gatewayName)
            assertFalse(response.durableIndexJobs)
            assertFalse(response.indexOutcomeUnknown)
            assertEquals(JsonPrimitive("phone"), response.result["map_source"])
            assertEquals(context.commitSha, response.result.getValue("graph").jsonObject.getValue("commit_sha").jsonPrimitive.content)
            assertEquals(1, repositoryReads)
        }
    }

    @Test fun disabledGitHubPolicyRejectsBeforeAnyTransport() = runTest {
        features = features.copy(githubProfileOverrides = mapOf(profile.uid to GitHubChatProfileOverride(githubTools = false)))
        http().use { transport ->
            val failure = runCatching { repository(transport).executeForMap(context, action("status")) }.exceptionOrNull() as GatewayGraphOperationException
            assertEquals("PERMISSION_DENIED", failure.failureCode)
            assertEquals(0, requests)
        }
    }

    @Test fun privacyGateRejectsBeforeTransportWithoutLosingTheTypedFailure() = runTest {
        privacyEnabled = true
        http().use { transport ->
            val failure = runCatching { repository(transport).executeForMap(context, action("status")) }.exceptionOrNull() as GatewayGraphOperationException
            assertTrue(failure.isLocalPrivacyPolicyRejection())
            assertEquals("PRIVACY_SERVICE_UNVERIFIED", failure.failureCode)
            assertEquals("REVIEW_PRIVACY_MODE", failure.repairAction)
            assertFalse(failure.indexOutcomeUnknown)
            assertEquals(0, requests)
        }
    }

    @Test fun privacyEnabledAfterRepositoryCheckBlocksTheNextSourceRequest() = runTest {
        afterRepository = { privacyEnabled = true }
        http().use { transport ->
            val failure = runCatching { repository(transport).executeForMap(context, action("status")) }.exceptionOrNull() as GatewayGraphOperationException
            assertTrue(failure.isLocalPrivacyPolicyRejection())
            assertEquals("PRIVACY_SERVICE_UNVERIFIED", failure.failureCode)
            assertEquals("REVIEW_PRIVACY_MODE", failure.repairAction)
            assertFalse(failure.indexOutcomeUnknown)
            assertEquals(1, repositoryReads)
            assertEquals(1, requests)
        }
    }

    @Test fun aChangedAccountCannotReleasePreviouslyFetchedTreeData() = runTest {
        afterTree = { account = "another-account" }
        http().use { transport ->
            val failure = runCatching { repository(transport).executeForMap(context, action("status")) }.exceptionOrNull() as GatewayGraphOperationException
            assertEquals("GRAPH_SELECTION_CHANGED", failure.failureCode)
            assertFalse(failure.indexOutcomeUnknown)
        }
    }

    @Test fun removingTheConnectionDuringSourceReadRejectsItsLateResult() = runTest {
        afterTree = { connection = null }
        http().use { transport ->
            val failure = runCatching { repository(transport).executeForMap(context, action("status")) }.exceptionOrNull() as GatewayGraphOperationException
            assertEquals("PERMISSION_REVOKED", failure.failureCode)
            assertFalse(failure.indexOutcomeUnknown)
        }
    }

    @Test fun cachedMapStillChecksCurrentRepositoryAuthorization() = runTest {
        http().use { transport ->
            val reader = repository(transport)
            reader.executeForMap(context, action("status"))
            repositoryStatus = HttpStatusCode.Forbidden
            val failure = runCatching { reader.executeForMap(context, action("architecture")) }.exceptionOrNull()
            assertTrue(failure is GatewayGraphOperationException)
            assertEquals("Cached source must not suppress current repository access checks", 2, repositoryReads)
        }
    }

    @Test fun callerCannotReplaceTheSelectedRevision() = runTest {
        http().use { transport ->
            val arguments = buildJsonObject {
                put("action", "status")
                put("commit_sha", "d".repeat(40))
            }
            val failure = runCatching { repository(transport).executeForMap(context, arguments) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("cannot replace"))
            assertEquals(0, requests)
        }
    }
}
