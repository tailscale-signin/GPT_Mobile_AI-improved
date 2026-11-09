package dev.chungjungsoo.gptmobile.data.llama

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaMetadataFailureTest {
    @Test fun arbitraryServerFieldsAndMalformedErrorBodiesNeverEnterDiagnostics() {
        val secret = "private-token-and-prompt"
        val body = Json.parseToJsonElement("""{"error":{"code":"$secret","type":"$secret","param":"$secret","message":"$secret"}}""").jsonObject
        val failure = llamaMetadataFailure(400, body, secret)
        assertEquals("METADATA_REQUEST_REJECTED", failure.code)
        assertNull(failure.serverCode)
        assertNull(failure.serverType)
        assertNull(failure.parameter)
        assertNull(failure.requestId)
        assertFalse(failure.diagnosticFields().toString().contains(secret))
        assertEquals(failure, llamaMetadataFailure(400, null))
        assertTrue(failure.message.length < 250)
    }

    @Test fun modelNotLoadedPreservesExactSelectionAndReportsActionableFixedGuidance() {
        val body = Json.parseToJsonElement("""{"error":{"code":"model_not_loaded","message":"private host path"}}""").jsonObject
        val failure = llamaMetadataFailure(400, body, "a".repeat(32))
        assertEquals("model_not_loaded", failure.serverCode)
        assertEquals("a".repeat(32), failure.requestId)
        assertTrue(failure.message.contains("Load it on the host"))
        assertFalse(failure.message.contains("private host path"))
        assertEquals(false, failure.diagnosticFields()["rawResponseRetained"])
    }
}
