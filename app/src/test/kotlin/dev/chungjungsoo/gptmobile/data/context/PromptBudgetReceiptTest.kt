package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.resolveOutputAllowance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetReceiptTest {
    private val runtime = RuntimeFingerprint("profile", "local", "none", "model", "model", "build", "template", "context")

    @Test fun finalComponentsPartitionTheMeasurementWithoutRetainingContents() {
        val secret = "private request text 😀"
        val payload = buildJsonObject {
            put("system", "System instructions")
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "USER")
                            put("text", "Earlier request")
                        }
                    )
                )
            )
            put("input", secret)
            put(
                "tools",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("name", "lookup")
                            put("description", "Schema ".repeat(500))
                        }
                    )
                )
            )
        }.toString()
        val measurement = RequestAdmission.estimate(payload).let { it.copy(promptTokens = it.promptTokens + 256) }
        val budget = PromptBudgets.capture(payload, measurement, 512, 8192, "unverified-template", attachmentReserveTokens = 256)
        assertEquals(measurement.promptTokens, budget.components.sumOf { it.tokens })
        assertEquals("tool_schemas", budget.dominantComponent?.name)
        assertEquals(TokenMeasurementKind.UTF8_ESTIMATE, budget.measurement)
        val exported = Json.encodeToString(budget)
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("Earlier request"))
        assertEquals(64, budget.originalRequestLineage.length)
    }

    @Test fun toolResultHeadroomIsReservedSeparatelyAndOnlyOnce() {
        val measurement = RequestMeasurement(3000, 96, TokenMeasurementKind.UTF8_ESTIMATE)
        val budget = PromptBudgets.capture("payload", measurement, 900, 4096, "template", toolResultReserveTokens = 100)
        val admitted = RequestAdmission.prepare("payload", "primary", CapacitySnapshot(runtime, 4096), measurement, 900, "permission", promptBudget = budget)
        assertEquals(196L, admitted.receipt.safetyTokens)
        assertEquals(100L, admitted.receipt.promptBudget?.toolResultReserveTokens)
        val rejection = runCatching {
            RequestAdmission.prepare("payload", "primary", CapacitySnapshot(runtime, 4096), measurement, 901, "permission", promptBudget = budget.copy(outputAllowanceTokens = 901))
        }.exceptionOrNull()
        assertTrue(rejection?.message.orEmpty().contains("tool-result reserve=100"))
    }

    @Test fun bridgeSchemasAreAttributedOnceEvenWhenEmbeddedInSystemInstructions() {
        val schema = "Authorized tools\n" + "input properties ".repeat(300)
        val payload = buildJsonObject {
            put("system", "Be clear.\n$schema\nKeep qualifications.")
            put("input", "Use lookup")
            put("messages", JsonArray(emptyList()))
            put("tools", JsonArray(emptyList()))
        }.toString()
        val measured = RequestAdmission.estimate(payload)
        val budget = PromptBudgets.capture(payload, measured, 512, 8192, "template", toolSchema = schema, embeddedToolSchema = schema)
        assertEquals("tool_schemas", budget.dominantComponent?.name)
        assertEquals(measured.promptTokens, budget.components.sumOf { it.tokens })
        assertTrue(budget.components.single { it.name == "system_instructions" }.tokens < 50)
    }

    @Test fun remoteHeadroomFailureExplainsTheDominantComponentBeforeDispatch() {
        val payload = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Question")
                        }
                    )
                )
            )
            put("tools", JsonArray(listOf(JsonPrimitive("schema ".repeat(1500)))))
        }.toString()
        val failure = runCatching { resolveOutputAllowance(payload, 4096, CapacitySnapshot(runtime, 2048)) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("CONTEXT_CAPACITY_EXCEEDED"))
        assertTrue(failure?.message.orEmpty().contains("tool_schemas"))
        assertTrue(failure?.message.orEmpty().contains("context=2048"))
    }

    @Test fun failedStageRecoveryKeepsTheSmallLocalContract() {
        val base = LocalRequestPrompt.build("Keep units", "", true)
        val retry = LocalRequestPrompt.build("Keep units", "", true, "RETRY_FAILED_STAGE")
        assertTrue(retry.startsWith(base))
        assertTrue(retry.length - base.length < 220)
        assertTrue(retry.contains("Do not repeat completed actions"))
    }
}
