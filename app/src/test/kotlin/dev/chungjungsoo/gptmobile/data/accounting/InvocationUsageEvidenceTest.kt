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

    @Test fun immutableProviderLaneIsIndependentOfTheAccountedReservationLabel() {
        val usage = """{"invocation_id":"invocation","input":{"source":"provider_reported_interim"},"cost_confirmed":false}"""
        val row = kotlinx.serialization.json.JsonObject(Json.parseToJsonElement("""{"id":"invocation","inputTokens":11736,"observedInputTokens":8838,"inputTokenSource":"estimated","costMicros":500}""").jsonObject + ("usageEvidenceJson" to JsonPrimitive(usage)))
        val evidence = invocationUsageEvidence(row)
        assertEquals(JsonPrimitive(8838), evidence["input_interim_reported"])
        assertEquals(JsonPrimitive(11736), evidence["input_accounted_or_reserved"])
        assertEquals(JsonPrimitive("estimate_or_reservation_floor"), evidence["input_accounting_source"])
        assertEquals(JsonNull, evidence["cost_final"])
        assertEquals(JsonNull, evidence["input_final_reported"])
    }
}
