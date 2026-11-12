package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

    @Test fun recoveryMetadataPreservesBaseIdentityButCannotHideAChangedApiContract() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"enum":["read"]}},"required":["action"]}""").jsonObject
        val original = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", schema)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Fingerprinting never executes a tool")
        }
        val recovery = ResponseRecoveryContext(emptyList(), emptyList(), emptyList(), rootWorkId = "saved-work")
        val wrapped = recovery.reuseCompletedTool(original, original.definition.name, "connection").definition
        assertEquals(ToolContractFingerprint.definition(original.definition), ToolContractFingerprint.definition(wrapped))
        assertNotEquals(ToolContractFingerprint.schema(schema), ToolContractFingerprint.schema(wrapped.inputSchema))
        val changed = wrapped.copy(inputSchema = JsonObject(wrapped.inputSchema + ("required" to Json.parseToJsonElement("""["action","new_required_field"]"""))))
        assertNotEquals(ToolContractFingerprint.definition(wrapped), ToolContractFingerprint.definition(changed))
        val realApiProperty = original.definition.copy(inputSchema = JsonObject(schema + ("properties" to buildJsonObject { put("_recovery_effect_id", buildJsonObject { put("type", "integer") }) })))
        assertNotEquals(ToolContractFingerprint.definition(original.definition), ToolContractFingerprint.definition(realApiProperty))
    }
}
