package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderResponseFormatPolicyTest {
    private val schema = """{"type":"object","properties":{"status":{"type":"string","enum":["ready"]},"count":{"type":"integer"}},"required":["status","count"],"additionalProperties":false}"""

    @Test fun llamaUsesItsSchemaBearingJsonObjectFormat() {
        val format = format(ClientType.LLAMA, schema)
        assertEquals(setOf("type", "schema"), format.keys)
        assertEquals("json_object", format.getValue("type").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(schema), format["schema"])
    }

    @Test fun openRouterUsesANamedSchemaWithoutInventingStricterConstraints() {
        val format = format(ClientType.OPENROUTER, """{"type":"object"}""")
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val envelope = format.getValue("json_schema").jsonObject
        assertEquals("final_response", envelope.getValue("name").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"type":"object"}"""), envelope["schema"])
        assertFalse(envelope.containsKey("strict"))
    }

    @Test fun namedStrictGithubSchemasAreNotWrappedTwiceOrWeakened() {
        val contract = """{"type":"json_schema","json_schema":{"name":"github_evidence_claims","strict":true,"schema":$schema}}"""
        for (provider in listOf(ClientType.LLAMA, ClientType.OPENROUTER)) {
            assertEquals(Json.parseToJsonElement(contract), format(provider, contract))
        }
    }

    @Test fun explicitResponseFormatsArePreserved() {
        for (contract in listOf("""{"type":"text"}""", """{"type":"json_object"}""", """{"type":"json_object","schema":$schema}""")) {
            for (provider in listOf(ClientType.LLAMA, ClientType.OPENROUTER)) {
                assertEquals(Json.parseToJsonElement(contract), format(provider, contract))
            }
        }
    }

    @Test fun nestedSchemaKeywordsAndUnionTypesAreRetained() {
        val contract = """{"type":["object","null"],"properties":{"value":{"anyOf":[{"type":"integer"},{"type":"string"}]}},"required":["value"]}"""
        val parsed = Json.parseToJsonElement(contract)
        assertEquals(parsed, format(ClientType.LLAMA, contract)["schema"])
        assertEquals(parsed, format(ClientType.OPENROUTER, contract).getValue("json_schema").jsonObject["schema"])
    }

    @Test fun textRequestsAndOtherProviderContractsStayUnchanged() {
        for (provider in ClientType.entries) {
            assertNull(ProviderResponseFormatPolicy.chatCompletions(provider, null))
            if (provider !in setOf(ClientType.LLAMA, ClientType.OPENROUTER)) {
                assertNull(ProviderResponseFormatPolicy.chatCompletions(provider, schema))
            }
        }
    }

    private fun format(provider: ClientType, schema: String): JsonObject = requireNotNull(ProviderResponseFormatPolicy.chatCompletions(provider, schema))
}
