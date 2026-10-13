package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestAdmissionTest {
    private val runtime = RuntimeFingerprint("connection", "https://server/prefix", "credential-v1", "model", "model", "epoch", "template", "allocation")

    @Test
    fun ix01ZeroMeansNoExtraCapAndDefaultsKeepTheirProvenance() {
        assertNull(RequestLimitResolver.output(0, null).tokens)
        assertEquals(16384, RequestLimitResolver.output(null, 16384).tokens)
        assertEquals(4096, RequestLimitResolver.output(4096, 16384).tokens)
        assertEquals(setOf(OutputLimitSource.USER_PROFILE), RequestLimitResolver.output(4096, 16384).sources)
        assertEquals(setOf(OutputLimitSource.APPLICATION_TARGET), RequestLimitResolver.output(null, null, applicationTarget = 16384).sources)
    }

    @Test
    fun lcT01BoundaryPassesOneTokenOverflowBlocksAndWireCannotChange() {
        val capacity = CapacitySnapshot(runtime, 4096)
        val request = RequestAdmission.prepare("full task", "primary", capacity, RequestMeasurement(3000, 96, TokenMeasurementKind.SERVING_TOKENIZER), 1000, "permission-v1")
        assertTrue(request.receipt.capacityChecked)
        assertEquals("full task", request.dispatchPayload(runtime, "permission-v1"))
        assertThrows(IllegalStateException::class.java) { request.dispatchPayload(runtime.copy(requestedModel = "other"), "permission-v1") }
        assertThrows(IllegalStateException::class.java) { request.dispatchPayload(runtime, "revoked") }
        assertThrows(IllegalStateException::class.java) { RequestAdmission.prepare("full task", "primary", capacity, RequestMeasurement(3001, 96, TokenMeasurementKind.SERVING_TOKENIZER), 1000, "permission-v1") }
    }

    @Test
    fun lcT02OverflowAndUnknownCapacityCannotBecomeSuccessfulBudgetChecks() {
        assertThrows(ArithmeticException::class.java) { RequestAdmission.prepare("", "assist", CapacitySnapshot(runtime, Long.MAX_VALUE), RequestMeasurement(Long.MAX_VALUE, 1, TokenMeasurementKind.UTF8_ESTIMATE), 1, "v1") }
        assertThrows(IllegalStateException::class.java) { RequestAdmission.prepare("", "combined", CapacitySnapshot(runtime, null), RequestMeasurement(1, 1, TokenMeasurementKind.UTF8_ESTIMATE), 1, "v1", requireKnownCapacity = true) }
    }

    @Test
    fun lcT03PropsUseActualAllocationWithoutBlindSlotDivision() {
        val config = ProviderRequestConfig("https://server/proxy/v1", "synthetic")
        val capacity = parseLlamaCapacity("""{"default_generation_settings":{"n_ctx":32768},"total_slots":4,"model":"model","chat_template":"template"}""", config, "model", 1)
        assertEquals(32768L, capacity?.contextTokens)
        assertNull(parseLlamaCapacity("""{"default_generation_settings":{"n_ctx":32768},"model":"wrong","chat_template":"template"}""", config, "model", 1))
        assertNull(parseLlamaCapacity("""{"n_ctx":131072,"total_slots":4}""", config, "model", 1))
    }
}
