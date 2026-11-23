package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDispatchContextTest {
    private val runtime = RuntimeFingerprint("connection", "route", "credential", "model", "model", "epoch", "template", "allocation")
    private val receipt = RequestAdmission.prepare("payload", "primary", CapacitySnapshot(runtime, 10000), RequestMeasurement(1, 1, TokenMeasurementKind.UTF8_ESTIMATE), 1, "permissions").receipt

    @Test fun physicalAttemptsKeepTheirOwnAdmittedProfileSnapshotsWithoutRetainingSecretsOrPromptBytes() {
        val context = RequestDispatchContext(emptySet())
        var config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig(
            "https://private.example/v1",
            "secret-credential",
            profileRevision = "a".repeat(64),
            configuredProfileReasoning = true,
            allowReasoning = true
        )
        context.serialized("""{"reasoning":{"effort":"high"},"input":"secret-prompt","token":"secret-credential"}""", receipt.copy(physicalAttemptId = "attempt-a", outputTokens = 90), config)
        val firstSnapshot = context.serializedRequestSettings.single()
        config = config.copy(profileRevision = "b".repeat(64), configuredProfileReasoning = false, allowReasoning = false, reasoningMode = ReasoningMode.OFF)
        context.serialized("""{"generationConfig":{"thinkingConfig":{"includeThoughts":false}},"contents":"changed-secret-prompt"}""", receipt.copy(physicalAttemptId = "attempt-b", outputTokens = 40), config)
        val snapshots = context.serializedRequestSettings
        assertEquals(firstSnapshot, snapshots.first())
        assertEquals(listOf(JsonPrimitive("attempt-a"), JsonPrimitive("attempt-b")), snapshots.map { it["physical_attempt_id"] })
        assertEquals(listOf(JsonPrimitive(90), JsonPrimitive(40)), snapshots.map { it["serialized_output_tokens"] })
        val settings = snapshots.map { it.getValue("settings").jsonObject }
        assertEquals(listOf(JsonPrimitive("a".repeat(64)), JsonPrimitive("b".repeat(64))), settings.map { it["profile_revision"] })
        assertEquals(listOf(JsonPrimitive(true), JsonPrimitive(false)), settings.map { it["configured_profile_reasoning"] })
        assertEquals(listOf(JsonPrimitive("reasoning.effort=high"), JsonPrimitive("generationConfig.thinkingConfig.includeThoughts=false")), settings.map { it["serialized_control"] })
        assertTrue(settings.all { it["effective_reasoning"] == JsonNull && it["effective_settings_source"] == JsonPrimitive("NOT_MEASURED") })
        assertTrue(snapshots.all { it["evidence_scope"] == JsonPrimitive("ADMITTED_REQUEST") })
        assertTrue(listOf("secret", "private.example", "contents", "input").none { it in snapshots.toString() })
        context.serialized("{}", receipt, config.copy(profileRevision = null))
        assertEquals(2, context.serializedRequestSettings.size)
    }

    @Test fun excessiveRetriesBoundSettingsWithoutOverwritingEarlierAttemptsOrHidingTheLatestSnapshot() {
        val context = RequestDispatchContext(emptySet())
        val config = dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig("https://private.example", "secret", profileRevision = "a".repeat(64))
        repeat(RequestDispatchContext.MAX_RETAINED_REQUEST_SETTINGS + 3) { attempt ->
            context.serialized("{}", receipt.copy(physicalAttemptId = "attempt-$attempt"), config)
        }
        assertEquals(RequestDispatchContext.MAX_RETAINED_REQUEST_SETTINGS, context.serializedRequestSettings.size)
        assertEquals(JsonPrimitive("attempt-0"), context.serializedRequestSettings.first()["physical_attempt_id"])
        assertEquals(3L, context.omittedSerializedRequestSettings)
        assertEquals(JsonPrimitive("attempt-${RequestDispatchContext.MAX_RETAINED_REQUEST_SETTINGS + 2}"), context.latestSerializedRequestSettings?.get("physical_attempt_id"))
    }

    @Test fun privateFinalWireRejectionProvesOnlyUnsentRequestsAndPreservesEarlierAttempts() {
        val proof = dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchContext.Builder(7, emptySet()).seal()
        val rejected = try {
            proof.validate("""{"messages":[{"content":"Public task"}]}""", ":privacy-8")
            error("Expected changed privacy authority to reject")
        } catch (rejected: dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchRejected) {
            rejected.receipt
        }
        val unsent = RequestDispatchContext(emptySet())
        unsent.privacyValidated(rejected, proof)
        assertTrue(unsent.provenEntirelyUnsent())
        assertTrue(unsent.receipts.isEmpty())
        val uncertainEarlierAttempt = RequestDispatchContext(emptySet())
        uncertainEarlierAttempt.receipts += receipt.copy(physicalAttemptId = "earlier-physical-attempt")
        uncertainEarlierAttempt.privacyValidated(rejected, proof)
        assertFalse(uncertainEarlierAttempt.provenEntirelyUnsent())
        assertEquals("earlier-physical-attempt", uncertainEarlierAttempt.receipts.single().physicalAttemptId)
    }

    @Test fun privateEvidenceKeepsEachPhysicalAttemptBoundedAndStreamsJoinItsRequest() {
        val context = RequestDispatchContext(emptySet())
        val proof = dev.chungjungsoo.gptmobile.data.privacy.PrivateDispatchContext.Builder(1, emptySet()).seal()
        val validation = proof.validate("""{"messages":[{"content":"Public task"}]}""", ":privacy-1")
        repeat(RequestDispatchContext.MAX_RETAINED_REQUEST_SETTINGS + 2) { index ->
            context.privacyValidated(kotlinx.serialization.json.JsonObject(validation + ("physical_attempt_id" to JsonPrimitive("attempt-$index"))), proof)
        }
        assertEquals(RequestDispatchContext.MAX_RETAINED_REQUEST_SETTINGS, context.privacyDispatchReceipts.size)
        assertEquals(2L, context.omittedPrivacyDispatchReceipts)
        assertEquals(JsonPrimitive("attempt-0"), context.privacyDispatchReceipts.first()["physical_attempt_id"])
        proof.stream(
            dev.chungjungsoo.gptmobile.data.privacy.PrivateStreamObservation(
                null, 10, 11, 500, 1, 0, 0, 0,
                dev.chungjungsoo.gptmobile.data.privacy.PrivateMarkerProtocolStatus.NO_ISSUED_MARKERS,
                emptySet(), true
            )
        )
        assertEquals(validation["private_request_id"], context.privacyStreams.single()["private_request_id"])
        assertEquals(JsonPrimitive(11), context.privacyStreams.single()["first_restored_answer_ms"])
        assertFalse(context.privacyDispatchReceipts.toString().contains("Public task"))
    }

    @Test
    fun ix09OnlyTheExactFinalSerializedHandoffCountsAsIncluded() {
        val candidate = Json.parseToJsonElement("""{"assistHandoff":{"producerInvocationId":"producer","artifactId":"artifact"},"assistExtracts":[{"text":"Exact source 10 kg"}]}""") as kotlinx.serialization.json.JsonObject
        val context = RequestDispatchContext(setOf(candidate))
        context.serialized("""{"messages":[{"role":"tool","content":"Source with no handoff"}]}""", receipt)
        assertTrue(context.includedProducers.isEmpty())
        context.serialized("""{"assist":[{"assistHandoff":{"producerInvocationId":"producer","artifactId":"artifact"},"assistExtracts":[{"text":"Different source 20 kg"}]}]}""", receipt)
        assertTrue(context.includedProducers.isEmpty())
        val encodedToolResult = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive("""{"data":"Raw remains","assist":[$candidate]}"""))
        context.serialized("""{"messages":[{"role":"tool","content":$encodedToolResult}]}""", receipt)
        assertEquals(setOf("producer"), context.includedProducers)
    }

    @Test fun preparedResearchEvidenceMustSurviveEntirelyInSerializedPrompt() {
        val packet = Json.parseToJsonElement("""{"claim":{"quote":"Exact source 10 kg"},"assistHandoffs":[{"producerInvocationId":"local-extractor"}]}""") as kotlinx.serialization.json.JsonObject
        val context = RequestDispatchContext(emptySet(), preparedEvidence = setOf(packet))
        context.serialized("""{"messages":[{"content":"local-extractor Exact source 10 kg"}]}""", receipt)
        assertTrue(context.includedProducers.isEmpty())
        val prompt = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive("Question\nUntrusted reference:\n$packet"))
        context.serialized("""{"messages":[{"content":$prompt}]}""", receipt)
        assertEquals(setOf("local-extractor"), context.includedProducers)
    }
}
