package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalRegionalIdentityTest {
    @Test fun regionalRoutesKeepModelFamiliesAndDeploymentsSeparate() {
        val direct = PlatformV2(uid = "direct", name = "fixture", compatibleType = ClientType.OPENAI, model = "gpt-4.1", apiUrl = "https://api.openai.com/v1")
        val routes = listOf("openrouter.ai", "eu.openrouter.ai", "us.openrouter.ai").map { host ->
            direct.copy(uid = host, compatibleType = ClientType.OPENROUTER, model = "openai/gpt-4.1", apiUrl = "https://$host/api/v1")
        }
        routes.forEach { route ->
            assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(direct, route))
            assertEquals("openai/gpt-4.1", CanonicalModelIdentity.resolve(route).canonical)
            assertNotEquals(CanonicalModelIdentity.resolve(direct).deployment, CanonicalModelIdentity.resolve(route).deployment)
        }
        assertEquals(3, routes.map { CanonicalModelIdentity.resolve(it).deployment }.distinct().size)
    }

    @Test fun regionalLookalikeHostsRemainUnresolved() {
        val profile = PlatformV2(name = "fixture", compatibleType = ClientType.OPENROUTER, model = "openai/gpt-4.1", apiUrl = "https://eu.openrouter.ai.example/api/v1")
        assertNull(CanonicalModelIdentity.resolve(profile).canonical)
    }
}
