package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyDispatchAdmissionTest {
    @Test fun localLlamaAndOllamaEndpointsKeepLocalOnlyContextWhileExternalEndpointsDoNot() {
        val llama = PlatformV2(name = "Phone Llama", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080")
        val ollama = PlatformV2(name = "LAN Ollama", compatibleType = ClientType.OLLAMA, apiUrl = "http://192.168.1.20:11434")
        val remote = PlatformV2(name = "Hosted Llama", compatibleType = ClientType.LLAMA, apiUrl = "https://models.example")

        assertTrue(localOnlyDispatchAllowed(llama.isPrivateDestination(), recalledLocalOnly = true, historyLocalOnly = true))
        assertTrue(localOnlyDispatchAllowed(ollama.isPrivateDestination(), recalledLocalOnly = true, historyLocalOnly = true))
        assertFalse(localOnlyDispatchAllowed(remote.isPrivateDestination(), recalledLocalOnly = true, historyLocalOnly = false))
        assertFalse(localOnlyDispatchAllowed(remote.isPrivateDestination(), recalledLocalOnly = false, historyLocalOnly = true))
        assertTrue(localOnlyDispatchAllowed(remote.isPrivateDestination(), recalledLocalOnly = false, historyLocalOnly = false))
    }
}
