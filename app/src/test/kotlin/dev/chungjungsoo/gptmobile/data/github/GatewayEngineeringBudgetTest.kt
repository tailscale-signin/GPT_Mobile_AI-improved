package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEngineeringBudgetTest {
    private val current = GatewayEngineeringCapabilities(
        "engineering-gateway",
        1,
        durationHours = listOf(3, 6, 12, 24, 0),
        executionBudgetLimits = GatewayEngineeringBudget(512, 8_388_608, 256, 256, 900)
    )

    @Test fun omittedPreferencesUseUltraAndRetainTheAcceptedBudgetInTheRequest() {
        val settings = Json.decodeFromString<GatewayEngineeringSettings>("{}")
        val selection = settings.resolveBudget(current, GitHubChatProfileOverride().engineeringMode, "Inspect repository", TokenBudgetSettings().totalRunTokens)
        assertEquals(24, selection.effectiveDurationHours)
        assertEquals(512, selection.effectiveBudget.modelCalls)
        assertEquals(2_097_152, selection.effectiveBudget.modelTokens)
        assertFalse(selection.limitedByHost)
        val request = request(selection)
        val encoded = Json.encodeToString(request)
        assertEquals(24, Json.parseToJsonElement(encoded).jsonObject.getValue("duration_hours").jsonPrimitive.int)
        assertEquals(selection.effectiveBudget, Json.decodeFromString<GatewayEngineeringRequest>(encoded).executionBudget)
        val checkpoint = GatewayEngineeringBinding("profile", "http://gateway", request, budgetSelection = selection)
        assertEquals(checkpoint, Json.decodeFromString<GatewayEngineeringBinding>(Json.encodeToString(checkpoint)))
    }

    @Test fun lowerModesAndExplicitCapsCannotAccidentallyBecomeUltra() {
        val expected = mapOf(EngineeringMode.QUICK to 32, EngineeringMode.STANDARD to 64, EngineeringMode.DEEP to 128, EngineeringMode.ENGINEERING_MAX to 256, EngineeringMode.ULTRA_MAX to 512)
        expected.forEach { (mode, calls) ->
            val result = GatewayEngineeringSettings().resolveBudget(current, mode, "Inspect repository")
            assertEquals(calls, result.effectiveBudget.modelCalls)
            assertEquals(calls * 4096, result.effectiveBudget.modelTokens)
        }
        val explicit = GatewayEngineeringSettings(3, executionBudget = GatewayEngineeringBudget(8, 8192))
        val result = explicit.resolveBudget(current, EngineeringMode.ULTRA_MAX, "Inspect repository", totalRunTokens = 4096)
        assertEquals(3, result.effectiveDurationHours)
        assertEquals(8, result.effectiveBudget.modelCalls)
        assertEquals(4096, result.effectiveBudget.modelTokens)
        assertFalse(result.limitedByHost)
        assertEquals(explicit, Json.decodeFromString<GatewayEngineeringSettings>(Json.encodeToString(explicit)))
        assertEquals(32, GatewayEngineeringSettings().resolveBudget(current, EngineeringMode.ADAPTIVE, "list repository files").effectiveBudget.modelCalls)
    }

    @Test fun olderGatewayLimitIsFrozenAndDescribedWithoutClaimingTheRequestedUltraAllowance() {
        val legacy = Json.decodeFromString<GatewayEngineeringCapabilities>("""{"service":"engineering-gateway","protocol_version":1,"duration_hours":[3,6,12,0]}""")
        val selection = GatewayEngineeringSettings().resolveBudget(legacy, EngineeringMode.ULTRA_MAX, "Inspect repository")
        assertEquals(24, selection.requestedDurationHours)
        assertEquals(512, selection.requestedBudget.modelCalls)
        assertEquals(12, selection.effectiveDurationHours)
        assertEquals(256, selection.effectiveBudget.modelCalls)
        assertTrue(selection.limitedByHost)
        val binding = GatewayEngineeringBinding("profile", "http://gateway", request(selection), budgetSelection = selection)
        val description = requireNotNull(binding.budgetDescription())
        assertTrue(description.contains("Requested"))
        assertTrue(description.contains("12 hours"))
        assertTrue(description.contains("256 model calls"))
        assertTrue(description.contains("lower allowance"))
        assertFalse(description.contains("24 hours"))
        assertFalse(description.contains("512 model calls"))
        val accepted = binding.copy(job = GatewayEngineeringJob("job", "owner/repo", "work", state = "PLAN", revision = 0, createdAt = 1.0, deadline = 43201.0, message = "Accepted", durationHours = 12, executionBudget = selection.effectiveBudget))
        assertTrue(requireNotNull(accepted.budgetDescription()).startsWith("Accepted"))
    }

    @Test fun legacyRetainedRequestKeepsThreeHoursAndNoInventedAcceptedBudget() {
        val legacy = Json.decodeFromString<GatewayEngineeringRequest>("""{"repository":"owner/repo","branch":"work","base":"main","objective":"Inspect","model":"selected","chat_id":7,"request_id":"saved"}""")
        assertEquals(3, legacy.durationHours)
        assertNull(legacy.executionBudget)
        val encoded = Json.parseToJsonElement(Json.encodeToString(legacy)).jsonObject
        assertEquals(3, encoded.getValue("duration_hours").jsonPrimitive.int)
        assertNull(encoded["execution_budget"])
        assertNull(GatewayEngineeringBinding("profile", "http://gateway", legacy).budgetDescription())
    }

    @Test fun unsupportedExplicitDurationCannotBecomeAnotherSavedChoice() {
        val unsupported = current.copy(durationHours = listOf(12))
        assertTrue(runCatching { GatewayEngineeringSettings(3).resolveBudget(unsupported, EngineeringMode.QUICK, "Inspect") }.isFailure)
        val untilComplete = GatewayEngineeringSettings(0).resolveBudget(current, EngineeringMode.ULTRA_MAX, "Inspect")
        assertEquals(0, untilComplete.effectiveDurationHours)
    }

    private fun request(selection: GatewayEngineeringBudgetSelection) = GatewayEngineeringRequest(
        "owner/repo", "work", "main", "Inspect", "selected",
        durationHours = selection.effectiveDurationHours, chatId = 7, requestId = "saved",
        executionBudget = selection.effectiveBudget
    )
}
