package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InvocationUsageEvidenceTest {
    @Test fun interimUsageNeverBecomesAReportedFinalOrAnEstimate() {
        val evidence = invocationUsageEvidence(Json.parseToJsonElement("""{"id":"invocation","inputTokens":8079,"observedInputTokens":5063,"inputTokenSource":"provider_reported_interim","outputTokens":2000,"outputTokenSource":"estimated"}""").jsonObject)
        assertEquals(JsonPrimitive(8079), evidence["input_accounted_or_reserved"])
        assertEquals(JsonPrimitive(5063), evidence["input_interim_reported"])
        assertEquals(JsonNull, evidence["input_final_reported"])
        assertEquals(JsonNull, evidence["input_estimate"])
        assertEquals(JsonPrimitive(2000), evidence["output_estimate"])
        assertEquals(JsonNull, evidence["output_final_reported"])
    }
}
