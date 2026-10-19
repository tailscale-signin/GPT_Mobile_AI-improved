package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ResilientStreamingClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageValidatorTest {
    @Test fun initialAssistantTailIsRejectedBeforeAdmissionAndCannotRetry() {
        for (payload in listOf(
            """{"messages":[{"role":"user","content":"Request"},{"role":"assistant","content":"Internal pending"}]}""",
            """{"contents":[{"role":"user","parts":[{"text":"Request"}]},{"role":"model","parts":[{"text":"Internal pending"}]}]}""",
            """{"input":[{"role":"user","content":"Request"},{"role":"assistant","content":"Internal pending"}]}"""
        )) {
            val failure = assertThrows(IllegalArgumentException::class.java) { ProviderMessageValidator.validate(payload) }
            assertTrue(failure.message!!.startsWith("INVALID_REQUEST_ROLE"))
            assertFalse(ResilientStreamingClient.isRetryable(failure))
        }
    }

    @Test fun explicitPrefillIsAnIndependentCapability() {
        ProviderMessageValidator.validate("""{"messages":[{"role":"user","content":"Request"},{"role":"assistant","content":"Intentional"}]}""", intentionalPrefill = true)
    }

    @Test fun toolResultsMustMatchEarlierCallsExactlyOnce() {
        val prefix = """{"messages":[{"role":"user","content":"Request"},{"role":"assistant","content":null,"tool_calls":[{"id":"a"},{"id":"b"}]}"""
        ProviderMessageValidator.validate(prefix + """,{"role":"tool","tool_call_id":"a","content":"Result"},{"role":"tool","tool_call_id":"b","content":"Result"}]}""")
        for (tail in listOf(
            """,{"role":"tool","tool_call_id":"a","content":"Result"}]}""",
            """,{"role":"tool","tool_call_id":"a","content":"Result"},{"role":"tool","tool_call_id":"a","content":"Duplicate"}]}""",
            """,{"role":"user","content":"Unpaired continuation"}]}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) { ProviderMessageValidator.validate(prefix + tail) }
        }
        ProviderMessageValidator.validate("""{"messages":[{"role":"user","content":[{"type":"text","text":"Request"}]},{"role":"assistant","content":[{"type":"tool_use","id":"a"}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"Result"}]}]}""")
    }

    @Test fun attachmentsAndUserQuotedInternalNoticesArePreserved() {
        ProviderMessageValidator.validate("""{"messages":[{"role":"user","content":[{"type":"text","text":"Quote [Combined turn: unavailable]"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}]}""")
        ProviderMessageValidator.validate("""{"contents":[{"role":"user","parts":[{"text":"Request"},{"inline_data":{"mime_type":"image/png","data":"AA=="}}]}]}""")
    }

    @Test fun responseToolOutputRequiresRetainedProviderContinuationIdentity() {
        val output = """"input":[{"type":"function_call_output","call_id":"a","output":"Result"}]"""
        assertThrows(IllegalArgumentException::class.java) { ProviderMessageValidator.validate("{$output}") }
        ProviderMessageValidator.validate("""{"previous_response_id":"retained-response",$output}""")
    }
}
