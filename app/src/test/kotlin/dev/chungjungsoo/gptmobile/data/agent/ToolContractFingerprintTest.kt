package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolContractFingerprintTest {
    @Test fun descriptionsOrderAndExamplesDoNotChangeInvocationIdentity() {
        val first = Json.parseToJsonElement("""{"type":"object","description":"head old","properties":{"branch":{"type":"string","description":"old"},"description":{"type":"string"}},"required":["branch","description"]}""").jsonObject
        val second = Json.parseToJsonElement("""{"required":["description","branch"],"properties":{"description":{"type":"string","examples":["hello"]},"branch":{"description":"head new","type":"string"}},"description":"new live context","type":"object"}""").jsonObject
        assertEquals(ToolContractFingerprint.schema(first), ToolContractFingerprint.schema(second))
    }

    @Test fun realPropertyAndPermissionConstraintChangesRemainSignificant() {
        val first = Json.parseToJsonElement("""{"type":"object","properties":{"description":{"type":"string"},"action":{"enum":["read"]}}}""").jsonObject
        val changedProperty = Json.parseToJsonElement("""{"type":"object","properties":{"description":{"type":"number"},"action":{"enum":["read"]}}}""").jsonObject
        val changedAction = Json.parseToJsonElement("""{"type":"object","properties":{"description":{"type":"string"},"action":{"enum":["read","write"]}}}""").jsonObject
        assertNotEquals(ToolContractFingerprint.schema(first), ToolContractFingerprint.schema(changedProperty))
        assertNotEquals(ToolContractFingerprint.schema(first), ToolContractFingerprint.schema(changedAction))
    }
}
