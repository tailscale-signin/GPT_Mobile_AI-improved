package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStructuredOutputTest {
    private val schema = """{"type":"object","properties":{"status":{"const":"ready"}},"required":["status"],"additionalProperties":false}"""

    @Test fun deltaBoundariesDoNotInsertAnOpeningPrefix() {
        val capture = LocalStructuredOutputCapture(schema)
        listOf("{", "\"status\":", "\"ready\"", "}").forEach(capture::append)
        assertNull(capture.failure())
    }

    @Test fun duplicatedNativePrefixIsRejectedWithoutRepair() {
        val capture = LocalStructuredOutputCapture(schema)
        listOf("{\"", "{\"status\":\"ready\"}").forEach(capture::append)
        assertEquals(LocalStructuredOutputFailure.MALFORMED, capture.failure()?.code)
    }

    @Test fun codeFencesTrailingTextAndMultipleObjectsAreNotStrictJson() {
        for (raw in listOf("```json\n{\"status\":\"ready\"}\n```", "{\"status\":\"ready\"} extra", "{\"status\":\"ready\"}{\"status\":\"ready\"}")) {
            assertEquals(LocalStructuredOutputFailure.MALFORMED, LocalStructuredOutputCapture(schema).apply { append(raw) }.failure()?.code)
        }
    }

    @Test fun schemaViolationsStayDistinctFromMalformedSyntax() {
        for (raw in listOf("{}", "{\"status\":\"wrong\"}", "{\"status\":\"ready\",\"extra\":1}", "[]")) {
            assertEquals(LocalStructuredOutputFailure.SCHEMA_MISMATCH, LocalStructuredOutputCapture(schema).apply { append(raw) }.failure()?.code)
        }
    }

    @Test fun validationMemoryLimitIsExplicitAndNeverAnAcceptedTruncation() {
        val capture = LocalStructuredOutputCapture(schema, maximumCharacters = 8)
        capture.append("{\"status\":\"ready\"}")
        assertEquals(LocalStructuredOutputFailure.VALIDATION_LIMIT, capture.failure()?.code)
    }

    @Test fun nativeGrammarErrorKeepsTypedIdentityAcrossWorkerBoundary() {
        val native = IllegalStateException("failed to commit token: parser error: token \"▁reasoning\" doesn't satisfy the grammar")
        val typed = structuredNativeFailure(native)
        assertTrue(typed is LocalStructuredOutputFailure)
        assertTrue(typed.cause === native)
        val qnn = classifyQnnFailure(QnnRuntimeStage.DECODE, typed)
        assertEquals(QnnFailureCode.CONSTRAINED_DECODING_FAILURE, qnn.code)
        val received = localRuntimeFailureFromRemote(localRuntimeErrorCode(qnn), qnn.message.orEmpty())
        assertTrue(received is LocalStructuredOutputFailure)
        assertFalse(received is LocalToolLimitException)
        assertTrue(received?.message.orEmpty().contains("▁reasoning"))
    }

    @Test fun unrelatedParserFailureAndCancellationAreNotGrammarFailures() {
        val other = IllegalStateException("parser error in downloaded metadata")
        assertFalse(isConstrainedDecodingFailure(other))
        assertTrue(structuredNativeFailure(other) === other)
        val cancelled = kotlinx.coroutines.CancellationException("constrained_decoding_failure cancelled")
        assertTrue(structuredNativeFailure(cancelled) === cancelled)
    }

    @Test fun ordinaryToolLimitKeepsItsExistingBoundary() {
        val error = LocalToolLimitException("CALLBACK_LIMIT")
        assertEquals("CALLBACK_LIMIT", localRuntimeErrorCode(error))
        assertTrue(localRuntimeFailureFromRemote(error.code, error.message.orEmpty()) is LocalToolLimitException)
    }
}
