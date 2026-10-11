package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderOptionsDraftTest {
    @Test fun unchangedDraftRetainsExactLegacyBytes() {
        val original = "{ \"temperature\": 0.5, \"future\": [1,2] }"
        assertEquals(original, mergeProviderOptionsDraft(original, "{\"temperature\":0.5}", "{\"temperature\":0.5}"))
    }

    @Test fun editsKeepUnknownNestedProviderFields() {
        val result = Json.parseToJsonElement(
            mergeProviderOptionsDraft(
                "{\"provider\":{\"order\":[\"a\"],\"future_policy\":true},\"future_root\":17}",
                "{\"provider\":{\"order\":[\"a\"]},\"stream\":true}",
                "{\"provider\":{\"order\":[\"b\"]},\"stream\":true}"
            )
        ).jsonObject
        assertEquals("17", result["future_root"]?.jsonPrimitive?.content)
        assertEquals("true", result["provider"]?.jsonObject?.get("future_policy")?.jsonPrimitive?.content)
        assertEquals("[\"b\"]", result["provider"]?.jsonObject?.get("order").toString())
        assertFalse(result.containsKey("stream"))
    }

    @Test fun explicitRemovalDoesNotResurrectOldKnownValue() {
        assertEquals("{\"future\":1}", mergeProviderOptionsDraft("{\"seed\":42,\"future\":1}", "{\"seed\":42}", "{}"))
    }
}
