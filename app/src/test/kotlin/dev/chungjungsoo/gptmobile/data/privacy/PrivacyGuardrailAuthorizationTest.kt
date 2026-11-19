package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardrailAuthorizationTest {
    @Test
    fun currentApprovalAllowsOnlyOnePhysicalGuardrailUpdate() {
        val fixture = Fixture()
        val interceptor = fixture.clientInterceptor()

        assertSame(fixture.response, interceptor.intercept(fixture.chain))
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 1) { fixture.chain.proceed(fixture.request) }
    }

    @Test
    fun revokedApprovalAfterClientCreationPreventsDispatch() {
        val fixture = Fixture()
        assertEquals(fixture.proof, fixture.currentApproval())
        val interceptor = fixture.clientInterceptor()
        fixture.records = emptyList()

        assertNull(fixture.currentApproval())
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun expiredCurrentEvidenceCannotAuthorizeTheEarlierPreparedUpdate() {
        val fixture = Fixture()
        assertEquals(fixture.proof, fixture.currentApproval())
        val interceptor = fixture.clientInterceptor()
        fixture.records = listOf(fixture.proof.copy(expiresAtMillis = System.currentTimeMillis() - 1))

        assertNull(fixture.currentApproval())
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun aDifferentValidApprovalDoesNotAuthorizeThePreparedGuardrail() {
        val fixture = Fixture()
        val interceptor = fixture.clientInterceptor()
        val replacement = fixture.proof.copy(guardrailId = "22222222-2222-2222-2222-222222222222")
        fixture.records = listOf(replacement)

        assertEquals(replacement, fixture.currentApproval())
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun removingTheLocalMutationOptInBeforeDispatchBlocksTheUpdate() {
        val fixture = Fixture()
        val interceptor = fixture.clientInterceptor()
        fixture.updatesAllowed = false

        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun localOptInCannotOverrideAnApprovalThatForbidsTightening() {
        val fixture = Fixture()
        val readOnlyProof = fixture.proof.copy(allowGuardrailTightening = false)
        fixture.records = listOf(readOnlyProof)
        val interceptor = fixture.clientInterceptor(readOnlyProof)

        assertEquals(readOnlyProof, fixture.currentApproval())
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun cancellationBeforeTheNetworkBoundaryPreventsDispatch() {
        val fixture = Fixture()
        val interceptor = fixture.clientInterceptor()
        fixture.cancelled = true

        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 0) { fixture.chain.proceed(any()) }
    }

    @Test
    fun anUnknownFirstMutationOutcomeCannotBeReplayedByTheClient() {
        val fixture = Fixture()
        val interceptor = fixture.clientInterceptor()
        every { fixture.chain.proceed(fixture.request) } throws IOException("Simulated lost response")

        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }
        assertThrows(IOException::class.java) { interceptor.intercept(fixture.chain) }

        verify(exactly = 1) { fixture.chain.proceed(fixture.request) }
    }

    @Test
    fun pendingIntentSurvivesRecreationRenewedPermissionAndForgetUntilMatchingConfirmation() {
        val values = mutableMapOf<String, Any>("issuer" to "test issuer", "signed" to "test evidence")
        val preferences = mockk<SharedPreferences>()
        val context = mockk<Context>()
        val vault = mockk<SecretVault>()
        every { context.getSharedPreferences("privacy_authority", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers { values[firstArg<String>()] as? String ?: secondArg() }
        every { preferences.getBoolean(any(), any()) } answers { values[firstArg<String>()] as? Boolean ?: secondArg() }
        every { preferences.edit() } answers {
            val pending = mutableMapOf<String, Any?>()
            var clearAll = false
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers {
                pending[firstArg()] = secondArg<String?>()
                editor
            }
            every { editor.putBoolean(any(), any()) } answers {
                pending[firstArg()] = secondArg<Boolean>()
                editor
            }
            every { editor.remove(any()) } answers {
                pending[firstArg()] = null
                editor
            }
            every { editor.clear() } answers {
                clearAll = true
                editor
            }
            every { editor.commit() } answers {
                if (clearAll) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                true
            }
            editor
        }
        val first = "a".repeat(64)
        val different = "b".repeat(64)
        val original = ProviderApprovalSource(context, vault)
        assertTrue(original.beginGuardrailUpdate(first))

        val recreated = ProviderApprovalSource(context, vault)
        assertFalse(recreated.beginGuardrailUpdate(first))
        assertFalse(recreated.beginGuardrailUpdate(different))
        repeat(2) {
            recreated.allowGuardrailUpdates()
            assertTrue(recreated.mayUpdateGuardrail())
            assertFalse(recreated.beginGuardrailUpdate(first))
            assertFalse(recreated.beginGuardrailUpdate(different))
        }
        recreated.forget()
        assertFalse(recreated.mayUpdateGuardrail())
        assertEquals(mapOf("pending_guardrail_update" to first), values)
        assertFalse(recreated.beginGuardrailUpdate(first))
        recreated.allowGuardrailUpdates()
        assertFalse(recreated.beginGuardrailUpdate(first))
        recreated.confirmGuardrailUpdate(different)
        assertFalse(recreated.beginGuardrailUpdate(first))
        recreated.confirmGuardrailUpdate(first)
        assertTrue(recreated.beginGuardrailUpdate(different))
    }

    private class Fixture {
        val profile = PlatformV2(
            name = "Privacy boundary test",
            compatibleType = ClientType.OPENROUTER,
            apiUrl = "https://openrouter.ai/api/v1",
            model = "example/model",
            token = "test-credential"
        )
        val url = profile.apiUrl + "/chat/completions"
        private val now = System.currentTimeMillis()
        val proof = ProviderApproval(
            endpoint = url,
            model = profile.model,
            credentialSha256 = OpenRouterIdentity.digest(profile.token.orEmpty()),
            upstream = "provider",
            policyUrl = PrivacyProfileEligibility.OPENROUTER_TERMS,
            verifiedAtMillis = now - 1_000,
            expiresAtMillis = now + 3_600_000,
            feature = "stateless-text-v1",
            retentionTier = "zdr",
            noTraining = true,
            loggingDisabled = true,
            externalToolsDisabled = true,
            providerDefaultsDisabled = true,
            safetyRetentionExcluded = true,
            guardrailId = "11111111-1111-1111-1111-111111111111",
            providerKeyHash = "a".repeat(64),
            allowGuardrailTightening = true,
            policySha256 = "b".repeat(64)
        )
        var records = listOf(proof)
        var updatesAllowed = true
        var cancelled = false
        private val authority = mockk<ProviderApprovalSource>()
        private val call = mockk<Call>()
        val chain = mockk<Interceptor.Chain>()
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/guardrails/" + proof.guardrailId)
            .patch("{}".toRequestBody())
            .build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()

        init {
            every { authority.records() } answers { records }
            every { authority.mayUpdateGuardrail() } answers { updatesAllowed }
            every { call.isCanceled() } answers { cancelled }
            every { chain.call() } returns call
            every { chain.request() } returns request
            every { chain.proceed(request) } returns response
        }

        private val verifier = PrivacyProviderVerifier(mockk(), mockk(), authority, mockk(), mockk())

        fun currentApproval() = verifier.approval(profile, url, proof.policyUrl)
        fun clientInterceptor(approval: ProviderApproval = proof): Interceptor =
            verifier.guardrailUpdateClient(profile, url, approval).networkInterceptors.single()
    }
}
