package dev.chungjungsoo.gptmobile.data.nvidia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NvidiaEndpointsTest {
    @Test
    fun normalizesHostedAndCustomDeploymentRoutesWithoutDuplicatingOperations() {
        assertEquals("https://integrate.api.nvidia.com/v1", NvidiaEndpoints.normalize("https://integrate.api.nvidia.com"))
        assertEquals("https://integrate.api.nvidia.com/v1", NvidiaEndpoints.normalize("HTTPS://INTEGRATE.API.NVIDIA.COM"))
        assertEquals("https://integrate.api.nvidia.com/v1", NvidiaEndpoints.normalize("https://integrate.api.nvidia.com/v1/chat/completions"))
        assertEquals("https://integrate.api.nvidia.com/v1", NvidiaEndpoints.normalize("https://integrate.api.nvidia.com/v1/models"))
        assertEquals("http://localhost:8000/v1", NvidiaEndpoints.normalize("http://localhost:8000/v1/"))
    }

    @Test
    fun secretsAndDowngradedHostedEndpointsAreNotAccepted() {
        assertThrows(IllegalArgumentException::class.java) {
            NvidiaEndpoints.normalize("https://name:secret@integrate.api.nvidia.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NvidiaEndpoints.normalize("http://integrate.api.nvidia.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NvidiaEndpoints.normalize("https://integrate.api.nvidia.com/v1?key=unsafe")
        }
    }
}
