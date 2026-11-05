package dev.chungjungsoo.gptmobile.data.llama

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaInferenceProbeEvidenceTest {
    @Test fun partialOutputWithoutATerminalFrameCannotProveInferenceReadiness() {
        val evidence = LlamaInferenceProbeEvidence("selected")
        evidence.observe("""{"model":"selected","choices":[{"delta":{"content":"OK"}}]}""")
        assertTrue(evidence.hasPayload)
        assertFalse(evidence.ready)
        evidence.observe("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertTrue(evidence.ready)
    }

    @Test fun wrongModelOrMalformedChunksCannotBeRescuedByATerminalMarker() {
        listOf("not-json", """{"model":"other","choices":[{"delta":{"content":"OK"}}]}""").forEach { payload ->
            val evidence = LlamaInferenceProbeEvidence("selected")
            evidence.observe(payload)
            evidence.completed = true
            assertFalse(evidence.ready)
        }
    }

    @Test fun disabledToolsMustNotBeRequestedByTheProbeResponse() {
        val evidence = LlamaInferenceProbeEvidence("selected")
        evidence.observe("""{"model":"selected","choices":[{"delta":{"content":"OK","tool_calls":[{"id":"tool"}]},"finish_reason":"stop"}]}""")
        assertTrue(evidence.hasPayload)
        assertFalse(evidence.ready)
    }

    @Test fun reasoningOnlyTokenCountsCanVerifyGenerationButNeverInventVisibleFirstTokenEvidence() {
        val evidence = LlamaInferenceProbeEvidence("selected")
        evidence.observe("""{"model":"selected","choices":[],"usage":{"completion_tokens":1}}""")
        evidence.completed = true
        assertTrue(evidence.ready)
        assertFalse(evidence.hasPayload)
    }
}
