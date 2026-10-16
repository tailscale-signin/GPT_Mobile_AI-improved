package dev.chungjungsoo.gptmobile.data.nvidia

import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NvidiaRequestPolicyTest {
    private fun request(model: String) = ChatCompletionRequest(
        model = model, messages = emptyList(), temperature = 0.6f, topK = 30
    )

    @Test
    fun gptOssUsesDocumentedMinimumEffortWhenThinkingIsOff() {
        val before = request("openai/gpt-oss-20b")
        val after = NvidiaRequestPolicy.prepare(before, reasoningRequested = false, reasoningAllowed = false)
        assertEquals("low", after.reasoningEffort)
        assertNull(after.topK)
        assertEquals(30, before.topK)
        assertNull(after.chatTemplateKwargs)
        assertTrue(NvidiaRequestPolicy.effectiveControl(before.model, false, false)!!.contains("mandatory"))
    }

    @Test
    fun gptOssCanSelectMediumEffort() {
        assertEquals("medium", NvidiaRequestPolicy.prepare(request("openai/gpt-oss-120b"), true, true).reasoningEffort)
        assertEquals("low", NvidiaRequestPolicy.prepare(request("openai/gpt-oss-120b"), true, false).reasoningEffort)
    }

    @Test
    fun nemotronThinkingSwitchIsSerializedOnlyWhenModelIsKnown() {
        val on = NvidiaRequestPolicy.prepare(request("nvidia/nemotron-3-super-120b-a12b"), true, true)
        assertEquals("true", on.chatTemplateKwargs?.get("enable_thinking").toString())
        assertNull(on.reasoningEffort)
        val off = NvidiaRequestPolicy.prepare(request("nvidia/nemotron-3-nano-30b-a3b"), true, false)
        assertEquals("false", off.chatTemplateKwargs?.get("enable_thinking").toString())
    }

    @Test
    fun unknownModelsDoNotReceiveGuessedParameters() {
        val before = request("acme/unknown")
        assertSame(before, NvidiaRequestPolicy.prepare(before, true, false))
        assertNull(NvidiaRequestPolicy.effectiveControl(before.model, true, false))
        assertNull(NvidiaModelCapabilities.forModel(before.model).supportsTools)
        assertFalse(NvidiaModelCapabilities.suggestedModelIds.isEmpty())
    }
}
