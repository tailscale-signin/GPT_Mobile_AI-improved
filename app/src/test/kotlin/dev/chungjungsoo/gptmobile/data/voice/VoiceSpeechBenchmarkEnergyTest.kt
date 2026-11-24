package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceSpeechBenchmarkEnergyTest {
    private fun observation() = VoiceSpeechBenchmarkEnergyObservation(
        VoiceSpeechBackend.CPU,
        VoiceSpeechEnergyEndpoint(12_000_000_000, VoiceSpeechEnergyChargeState.DISCHARGING, false),
        VoiceSpeechEnergyEndpoint(11_995_000_000, VoiceSpeechEnergyChargeState.DISCHARGING, false),
        elapsedMs = 3000,
        monitorRegistered = true,
        monitoringConfirmed = true,
        observedPowerEvents = 2
    )

    @Test fun monitoredDischargeRetainsRawNanowattHourEndpointsAndOnlyTheObservedDecrease() {
        val observation = observation()
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED, observation.status)
        assertEquals(5_000_000L, observation.observedCounterDecreaseNanowattHours)
        val json = speechBenchmarkEnergyJson(observation)
        assertEquals(JsonPrimitive(12_000_000_000L), json.getValue("start").jsonObject["remainingNanowattHours"])
        assertEquals(JsonPrimitive(5_000_000L), json["observedCounterDecreaseNanowattHours"])
        assertEquals(observation, readSpeechBenchmarkEnergy(json))
    }

    @Test fun endpointOnlyEvidenceCannotEstablishNoInterveningPowerConnection() {
        for (registered in listOf(false, true)) {
            val observation = observation().copy(monitorRegistered = registered, monitoringConfirmed = false)
            assertEquals(VoiceSpeechEnergyStatus.MONITOR_UNCONFIRMED, observation.status)
            assertNull(observation.observedCounterDecreaseNanowattHours)
            assertEquals(observation, readSpeechBenchmarkEnergy(speechBenchmarkEnergyJson(observation)))
        }
    }

    @Test fun chargingAtEitherEndpointOrBetweenEndpointsPreventsAnEnergyComparison() {
        val original = observation()
        for (value in listOf(
            original.copy(start = original.start.copy(plugged = true)),
            original.copy(end = original.end!!.copy(chargeState = VoiceSpeechEnergyChargeState.CHARGING)),
            original.copy(start = original.start.copy(chargeState = VoiceSpeechEnergyChargeState.FULL)),
            original.copy(externalPowerObserved = true)
        )) {
            assertEquals(VoiceSpeechEnergyStatus.EXTERNAL_POWER_OBSERVED, value.status)
            assertNull(value.observedCounterDecreaseNanowattHours)
        }
    }

    @Test fun missingCountersAndPowerStatesStayDistinctAndNeverBecomeZeroConsumption() {
        val original = observation()
        for (value in listOf(original.copy(start = original.start.copy(remainingNanowattHours = null)), original.copy(end = original.end!!.copy(remainingNanowattHours = null)))) {
            assertEquals(VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE, value.status)
            assertNull(value.observedCounterDecreaseNanowattHours)
        }
        for (value in listOf(
            original.copy(start = original.start.copy(plugged = null)),
            original.copy(end = original.end!!.copy(chargeState = VoiceSpeechEnergyChargeState.UNKNOWN)),
            original.copy(end = original.end!!.copy(chargeState = VoiceSpeechEnergyChargeState.NOT_CHARGING)),
            original.copy(unknownPowerStateObserved = true)
        )) {
            assertEquals(VoiceSpeechEnergyStatus.POWER_STATE_UNKNOWN, value.status)
            assertNull(value.observedCounterDecreaseNanowattHours)
        }
    }

    @Test fun counterQuantizationIncreaseOrZeroCannotBeReportedAsEnergySavings() {
        val original = observation()
        val unchanged = original.copy(end = original.start)
        val increased = original.copy(end = original.end!!.copy(remainingNanowattHours = 12_001_000_000))
        val reset = original.copy(end = original.end!!.copy(remainingNanowattHours = 0))
        val zero = original.copy(start = original.start.copy(remainingNanowattHours = 0), end = original.end!!.copy(remainingNanowattHours = 0))
        assertEquals(VoiceSpeechEnergyStatus.NO_COUNTER_CHANGE, unchanged.status)
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_INCREASED, increased.status)
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_ZERO_OR_RESET, reset.status)
        assertEquals(VoiceSpeechEnergyStatus.COUNTER_ZERO_OR_RESET, zero.status)
        for (value in listOf(unchanged, increased, reset, zero)) {
            assertNull(value.observedCounterDecreaseNanowattHours)
            assertEquals(JsonNull, speechBenchmarkEnergyJson(value)["observedCounterDecreaseNanowattHours"])
        }
    }

    @Test fun noElapsedWindowMeansEndpointsOnlyAndInProgressCannotClaimMonitorCompletion() {
        val original = observation()
        for (elapsed in listOf(null, 0L)) {
            val value = original.copy(elapsedMs = elapsed)
            assertEquals(VoiceSpeechEnergyStatus.TIMING_UNAVAILABLE, value.status)
            assertNull(value.observedCounterDecreaseNanowattHours)
        }
        val pending = original.copy(end = null, elapsedMs = null, monitoringConfirmed = false)
        assertEquals(VoiceSpeechEnergyStatus.IN_PROGRESS, pending.status)
        assertNull(pending.observedCounterDecreaseNanowattHours)
        assertEquals(pending, readSpeechBenchmarkEnergy(speechBenchmarkEnergyJson(pending)))
        assertThrows(IllegalArgumentException::class.java) { pending.copy(monitoringConfirmed = true) }
        val interrupted = pending.copy(interrupted = true)
        assertEquals(VoiceSpeechEnergyStatus.INTERRUPTED, interrupted.status)
        assertNull(interrupted.end)
        assertNull(interrupted.observedCounterDecreaseNanowattHours)
        assertEquals(interrupted, readSpeechBenchmarkEnergy(speechBenchmarkEnergyJson(interrupted)))
        assertThrows(IllegalArgumentException::class.java) { original.copy(interrupted = true) }
    }

    @Test fun impossibleRangesAndInventedDerivedClaimsAreRejectedWhenHistoryIsRead() {
        val json = speechBenchmarkEnergyJson(observation())
        for (changes in listOf(
            mapOf("elapsedMs" to JsonPrimitive(-1)),
            mapOf("elapsedMs" to JsonPrimitive(86_400_001L)),
            mapOf("observedPowerEvents" to JsonPrimitive(1_000_001)),
            mapOf("observedPowerEvents" to JsonPrimitive(-1)),
            mapOf("monitorRegistered" to JsonPrimitive(false)),
            mapOf("status" to JsonPrimitive("NO_COUNTER_CHANGE")),
            mapOf("observedCounterDecreaseNanowattHours" to JsonPrimitive(4_000_000L)),
            mapOf("end" to JsonNull),
            mapOf("start" to JsonObject(json.getValue("start").jsonObject + ("remainingNanowattHours" to JsonPrimitive(Long.MIN_VALUE))))
        )) {
            assertThrows(IllegalArgumentException::class.java) { readSpeechBenchmarkEnergy(JsonObject(json + changes)) }
        }
    }

    @Test fun maximumLongCountersCannotOverflowSubtraction() {
        val original = observation()
        val large = original.copy(start = original.start.copy(remainingNanowattHours = Long.MAX_VALUE), end = original.end!!.copy(remainingNanowattHours = 1))
        assertEquals(Long.MAX_VALUE - 1, large.observedCounterDecreaseNanowattHours)
        assertEquals(large, readSpeechBenchmarkEnergy(speechBenchmarkEnergyJson(large)))
    }
}
