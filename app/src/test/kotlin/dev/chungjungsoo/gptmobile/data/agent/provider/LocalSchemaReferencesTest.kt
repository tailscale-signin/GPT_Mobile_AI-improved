package dev.chungjungsoo.gptmobile.data.agent.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSchemaReferencesTest {
    private fun resolve(source: String) = resolveLocalSchemaReferences(Json.parseToJsonElement(source).jsonObject)

    @Test fun sharedAndEscapedReferencesKeepConstraints() {
        val source = """{"type":"object","properties":{"location":{"${'$'}ref":"#/${'$'}defs/a~1b~0c"},"other":{"${'$'}ref":"#/${'$'}defs/a~1b~0c"}},"${'$'}defs":{"a/b~c":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}"""
        val resolved = resolve(source)
        val properties = resolved["properties"]!!.jsonObject
        assertEquals(properties["location"], properties["other"])
        assertEquals("object", properties["location"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("""["city"]""", properties["location"]!!.jsonObject["required"].toString())
    }

    @Test fun cyclesMissingAndExternalReferencesFailClosed() {
        listOf(
            """{"${'$'}ref":"#/${'$'}defs/a","${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/a"}}}""",
            """{"${'$'}ref":"#/${'$'}defs/missing"}""",
            """{"${'$'}ref":"https://example.com/schema"}"""
        ).forEach { assertTrue(runCatching { resolve(it) }.isFailure) }
    }

    @Test fun propertyNamesAndExamplePayloadsAreNotSchemaKeywords() {
        val source = """{"type":"object","properties":{"${'$'}defs":{"type":"string"},"${'$'}ref":{"type":"integer"}},"examples":[{"${'$'}ref":"literal user data"}]}"""
        val resolved = resolve(source)
        assertEquals("string", resolved["properties"]!!.jsonObject["\$defs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(source).jsonObject["examples"], resolved["examples"])
    }
}
