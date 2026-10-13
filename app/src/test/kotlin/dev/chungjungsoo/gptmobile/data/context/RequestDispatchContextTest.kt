package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDispatchContextTest {
    private val runtime = RuntimeFingerprint("connection", "route", "credential", "model", "model", "epoch", "template", "allocation")
    private val receipt = RequestAdmission.prepare("payload", "primary", CapacitySnapshot(runtime, 10000), RequestMeasurement(1, 1, TokenMeasurementKind.UTF8_ESTIMATE), 1, "permissions").receipt

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
}
