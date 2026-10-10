package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryActionInputTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject
    private val schema = obj("""{"properties":{"query":{"type":"string"}},"required":["query"]}""")

    @Test
    fun legacyFlatAndEncodedInputsNormalizeToTheSameActionObject() {
        val expected = obj("""{"query":"remember"}""")
        assertEquals(expected, memoryActionInput(obj("""{"action":"recall","query":"remember"}"""), schema))
        assertEquals(expected, memoryActionInput(obj("""{"action":"recall","input":{"query":"remember"}}"""), schema))
        assertEquals(expected, memoryActionInput(obj("""{"action":"recall","input":"{\"query\":\"remember\"}"}"""), schema))
        assertNull(memoryActionInput(obj("""{"action":"recall","input":"not JSON"}"""), schema))
        assertNull(memoryActionInput(obj("""{"action":"recall","unexpected":"remember"}"""), schema))
        assertNull(memoryActionInput(obj("""{"action":"recall"}"""), schema))
    }
}
