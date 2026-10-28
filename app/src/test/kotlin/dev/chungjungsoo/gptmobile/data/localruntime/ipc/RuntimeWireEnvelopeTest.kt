package dev.chungjungsoo.gptmobile.data.localruntime.ipc

import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeWireEnvelopeTest {
    private fun validate(
        version: Int = 1,
        requestId: String = "request-1",
        sessionId: String = "",
        generation: String = "",
        callId: String = "",
        sequence: Long = 0
    ) = RuntimeWireEnvelope.validate(version, requestId, sessionId, generation, callId, sequence)

    @Test fun acceptsLegacyEnvelopeWithoutGeneration() = validate()

    @Test fun acceptsMaximumLengthIdentifiers() =
        validate(requestId = "r".repeat(128), sessionId = "s".repeat(128), generation = "g".repeat(128), callId = "c".repeat(128), sequence = Long.MAX_VALUE)

    @Test fun rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) { validate(version = 2) }
    }

    @Test fun rejectsEmptyRequestId() {
        assertThrows(IllegalArgumentException::class.java) { validate(requestId = "") }
    }

    @Test fun rejectsOversizedRequestId() {
        assertThrows(IllegalArgumentException::class.java) { validate(requestId = "r".repeat(129)) }
    }

    @Test fun rejectsOversizedSessionId() {
        assertThrows(IllegalArgumentException::class.java) { validate(sessionId = "s".repeat(129)) }
    }

    @Test fun rejectsOversizedGeneration() {
        assertThrows(IllegalArgumentException::class.java) { validate(generation = "g".repeat(129)) }
    }

    @Test fun rejectsOversizedCallId() {
        assertThrows(IllegalArgumentException::class.java) { validate(callId = "c".repeat(129)) }
    }

    @Test fun rejectsNegativeSequence() {
        assertThrows(IllegalArgumentException::class.java) { validate(sequence = -1) }
    }
}
