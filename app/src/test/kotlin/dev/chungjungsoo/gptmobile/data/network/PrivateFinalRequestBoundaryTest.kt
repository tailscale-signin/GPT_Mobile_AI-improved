package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyCategory
import dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchContext
import dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchRejected
import dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchSurfaceKind
import dev.chungjungsoo.gptmobile.data.privacy.PrivateTextMask
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivateFinalRequestBoundaryTest {
    private val config = ProviderRequestConfig(
        apiUrl = "https://private-wire-fixture.example/v1",
        token = null,
        providerType = "OPENAI",
        permissionRevision = ":privacy-7",
        contextCeiling = 8_192
    )

    @Test fun bothPhysicalBoundariesRejectReintroducedValuesBeforeReservingAnAttempt() = runBlocking {
        listOf(false, true).forEach { queued ->
            val client = mockk<NetworkClient>()
            val preparation = ModelRequestPreparation(client)
            val (proof, _) = protectedRequest()
            val dispatch = RequestDispatchContext(emptySet())
            var attempts = 0
            val authority = RequestAuthorizationGuard(":privacy-7") {}
            try {
                withContext(proof + dispatch + authority + PhysicalAttemptBoundary { attempts++ }) {
                    val wire = payload("Mira Voss")
                    if (queued) preparation.prepareQueued(wire, config, "model", 64) else preparation.prepare(wire, config, "model", 64)
                }
                fail("Expected the final serializer boundary to reject known private values")
            } catch (expected: PrivateDispatchRejected) {
                assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), expected.receipt["outcome"])
            }
            assertEquals(0, attempts)
            assertTrue(dispatch.receipts.isEmpty())
            assertTrue(dispatch.provenEntirelyUnsent())
            assertEquals(1, dispatch.privacyDispatchReceipts.size)
            assertFalse(dispatch.privacyDispatchReceipts.toString().contains("Mira"))
        }
    }

    @Test fun bothPhysicalBoundariesBindSanitizedWireToTheActualAdmittedAttempt() = runBlocking {
        listOf(false, true).forEach { queued ->
            val preparation = ModelRequestPreparation(mockk())
            val (proof, text) = protectedRequest()
            val dispatch = RequestDispatchContext(emptySet())
            var attempts = 0
            val authority = RequestAuthorizationGuard(":privacy-7") {}
            val wire = withContext(proof + dispatch + authority + PhysicalAttemptBoundary { attempts++ }) {
                if (queued) preparation.prepareQueued(payload(text), config, "model", 64) else preparation.prepare(payload(text), config, "model", 64)
            }
            assertEquals(1, attempts)
            assertFalse(wire.contains("Mira Voss"))
            val evidence = dispatch.privacyDispatchReceipts.single()
            assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), evidence["outcome"])
            assertEquals(JsonPrimitive(dispatch.receipts.single().physicalAttemptId), evidence["physical_attempt_id"])
            assertFalse(dispatch.provenEntirelyUnsent())
        }
    }

    private fun protectedRequest(): Pair<PrivateDispatchContext, String> {
        val original = "Name: Mira Voss. Please write a reply."
        val mask = PrivateTextMask(setOf(PrivacyCategory.IDENTITIES), emptyList())
        val protected = mask.protectWithReceipt(original)
        val proof = PrivateDispatchContext.Builder(7, setOf(PrivacyCategory.IDENTITIES)).apply {
            record(PrivateDispatchSurfaceKind.USER_MESSAGE, original, protected)
        }.seal(mask.summary(), mask.issuedMarkers())
        return proof to protected.text
    }

    private fun payload(text: String) = """{"model":"model","messages":[{"role":"user","content":${JsonPrimitive(text)}}],"max_tokens":64}"""
}
