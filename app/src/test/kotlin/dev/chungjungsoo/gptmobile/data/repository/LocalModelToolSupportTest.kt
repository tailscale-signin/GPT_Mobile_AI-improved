package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelToolSupportTest {
    @Test
    fun llamaCppArtifactsDoNotReceiveToolSchemas() {
        assertFalse(LocalModelToolSupport.supportsTools(ArtifactRuntime.LLAMA_CPP))
        assertFalse(LocalModelToolSupport.supportsTools(ArtifactRuntime.GENIEX_LLAMA_CPP))
    }

    @Test
    fun nonLlamaArtifactsKeepTheirExistingToolSupport() {
        assertTrue(LocalModelToolSupport.supportsTools(ArtifactRuntime.GENIEX_QAIRT))
    }
}
