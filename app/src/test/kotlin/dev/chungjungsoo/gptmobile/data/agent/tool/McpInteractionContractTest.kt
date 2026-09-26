package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInteractionContractTest {
    private fun parse(value: String) = Json.parseToJsonElement(value)

    @Test
    fun declaredTypesRequiredFieldsAndBoundsAreChecked() {
        val schema = parse("""{"type":"object","required":["count"],"additionalProperties":false,"properties":{"count":{"type":"integer","minimum":1,"maximum":5},"label":{"type":"string","maxLength":4}}}""").jsonObject
        assertNull(schemaError(schema, parse("""{"count":2,"label":"ok"}""")))
        listOf("{}", """{"count":"2"}""", """{"count":9}""", """{"count":1,"extra":true}""", """{"count":1,"label":"too long"}""").forEach { assertNotNull(schemaError(schema, parse(it))) }
    }

    @Test
    fun unsupportedFormsAndRemoteSchemaReferencesAreNotSilentlyAccepted() {
        assertTrue(supportedFormField(parse("""{"type":"string","enum":["one","two"]}""").jsonObject))
        assertFalse(supportedFormField(parse("""{"type":"object"}""").jsonObject))
        assertFalse(supportedFormField(parse("""{"type":"string","pattern":".*"}""").jsonObject))
        assertNotNull(schemaError(parse("""{"${'$'}ref":"https://example.com/schema"}""").jsonObject, parse("{}")))
    }
}
