package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal fun speechBenchmarkEnergyJson(observation: VoiceSpeechBenchmarkEnergyObservation): JsonObject = buildJsonObject {
    put("requestedBackend", observation.requestedBackend.name)
    put("start", speechEnergyEndpointJson(observation.start))
    put("end", observation.end?.let(::speechEnergyEndpointJson) ?: JsonNull)
    put("elapsedMs", observation.elapsedMs?.let(::JsonPrimitive) ?: JsonNull)
    put("monitorRegistered", observation.monitorRegistered)
    put("monitoringConfirmed", observation.monitoringConfirmed)
    put("observedPowerEvents", observation.observedPowerEvents)
    put("externalPowerObserved", observation.externalPowerObserved)
    put("unknownPowerStateObserved", observation.unknownPowerStateObserved)
    put("interrupted", observation.interrupted)
    put("status", observation.status.name)
    put("observedCounterDecreaseNanowattHours", observation.observedCounterDecreaseNanowattHours?.let(::JsonPrimitive) ?: JsonNull)
}

private fun speechEnergyEndpointJson(endpoint: VoiceSpeechEnergyEndpoint): JsonObject = buildJsonObject {
    put("remainingNanowattHours", endpoint.remainingNanowattHours?.let(::JsonPrimitive) ?: JsonNull)
    put("chargeState", endpoint.chargeState.name)
    put("plugged", endpoint.plugged?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun readSpeechBenchmarkEnergy(value: JsonObject): VoiceSpeechBenchmarkEnergyObservation {
    fun endpoint(row: JsonObject) = VoiceSpeechEnergyEndpoint(
        remainingNanowattHours = row.getValue("remainingNanowattHours").takeUnless { it == JsonNull }?.jsonPrimitive?.long,
        chargeState = VoiceSpeechEnergyChargeState.valueOf(row.getValue("chargeState").jsonPrimitive.content),
        plugged = row.getValue("plugged").takeUnless { it == JsonNull }?.jsonPrimitive?.boolean
    )
    val observation = VoiceSpeechBenchmarkEnergyObservation(
        requestedBackend = VoiceSpeechBackend.valueOf(value.getValue("requestedBackend").jsonPrimitive.content),
        start = endpoint(value.getValue("start").jsonObject),
        end = value.getValue("end").takeUnless { it == JsonNull }?.jsonObject?.let(::endpoint),
        elapsedMs = value.getValue("elapsedMs").takeUnless { it == JsonNull }?.jsonPrimitive?.long,
        monitorRegistered = value.getValue("monitorRegistered").jsonPrimitive.boolean,
        monitoringConfirmed = value.getValue("monitoringConfirmed").jsonPrimitive.boolean,
        observedPowerEvents = value.getValue("observedPowerEvents").jsonPrimitive.int,
        externalPowerObserved = value.getValue("externalPowerObserved").jsonPrimitive.boolean,
        unknownPowerStateObserved = value.getValue("unknownPowerStateObserved").jsonPrimitive.boolean,
        interrupted = value["interrupted"]?.jsonPrimitive?.boolean ?: false
    )
    require(observation.end != null || observation.elapsedMs == null)
    require(value.getValue("status").jsonPrimitive.content == observation.status.name)
    require(value.getValue("observedCounterDecreaseNanowattHours").takeUnless { it == JsonNull }?.jsonPrimitive?.long == observation.observedCounterDecreaseNanowattHours)
    return observation
}
