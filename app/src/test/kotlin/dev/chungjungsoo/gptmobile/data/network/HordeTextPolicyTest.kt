package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HordeTextPolicyTest {
    @Test fun automaticSelectionUsesLiveAvailabilityWithoutChangingAnExplicitModel() {
        val catalog = Json.parseToJsonElement("""[{"name":"a/Qwen2.5-7B-Instruct","count":1},{"name":"z/Llama-3.1-8B-Instruct","count":4}]""").jsonArray
        assertEquals("z/Llama-3.1-8B-Instruct", HordeTextPolicy.select(catalog, "automatic"))
        assertEquals("a/Qwen2.5-7B-Instruct", HordeTextPolicy.select(catalog, "a/Qwen2.5-7B-Instruct"))
    }

    @Test fun automaticSelectionRequiresActiveSupportedInstructionWorkers() {
        val catalog = Json.parseToJsonElement(
            """[
            {"name":"raw-completion-model","count":8},
            {"name":"Qwen2.5-7B-Instruct","count":0},
            {"name":"DeepSeek-R1-Llama3-Instruct","count":2},
            {"name":"koboldcpp/Llama-3.1-8B-Instruct","count":1}
        ]"""
        ).jsonArray
        assertEquals("koboldcpp/Llama-3.1-8B-Instruct", HordeTextPolicy.select(catalog, "automatic"))
        assertNull(HordeTextPolicy.select(catalog, "raw-completion-model"))
        assertNull(HordeTextPolicy.select(catalog, "Qwen2.5-7B-Instruct"))
        assertEquals("CHATML", HordeTextPolicy.format("koboldcpp/Qwen2.5-7B-Instruct"))
        assertEquals("LLAMA3", HordeTextPolicy.format("koboldcpp/Llama-3.1-8B-Instruct"))
    }
}
