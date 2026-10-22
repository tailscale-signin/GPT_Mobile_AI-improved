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

    @Test fun officialAndRouterDateAndThinkingAliasesRemainTheSameModelFamily() {
        val direct = PlatformV2(uid = "anthropic-direct", name = "Primary", compatibleType = ClientType.ANTHROPIC, model = "claude-3-7-sonnet-20250219", apiUrl = "https://api.anthropic.com")
        val routed = direct.copy(uid = "router", name = "Independent reviewer", compatibleType = ClientType.OPENROUTER, model = "anthropic/claude-3.7-sonnet:thinking", apiUrl = "https://openrouter.ai/api/v1")
        assertEquals("anthropic/claude-sonnet-3-7", CanonicalModelIdentity.resolve(direct).canonical)
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(direct, routed))
        assertNotEquals(CanonicalModelIdentity.resolve(direct).deployment, CanonicalModelIdentity.resolve(routed).deployment)
        val openAi = PlatformV2(uid = "openai-direct", name = "Primary", model = "gpt-4.1-2025-04-14", apiUrl = "https://api.openai.com/v1")
        val alias = openAi.copy(uid = "chatgpt-route", model = "openai/chatgpt-4.1-latest", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1")
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(openAi, alias))
    }

    @Test fun knownProviderNamespaceAliasesDoNotCountAsIndependentDeployments() {
        val nvidia = PlatformV2(uid = "nvidia", name = "NVIDIA", model = "meta/llama-3.3-70b-instruct", apiUrl = "https://integrate.api.nvidia.com/v1")
        val routed = nvidia.copy(uid = "router", model = "meta-llama/llama-3.3-70b-instruct:free", apiUrl = "https://openrouter.ai/api/v1", compatibleType = ClientType.OPENROUTER)
        assertEquals("meta-llama/llama-3.3-70b-instruct", CanonicalModelIdentity.resolve(nvidia).canonical)
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(nvidia, routed))
        val groq = PlatformV2(uid = "groq", name = "Groq", model = "gpt-oss-120b", apiUrl = "https://api.groq.com/openai/v1")
        val named = groq.copy(uid = "nvidia-oss", model = "openai/gpt-oss-120b", apiUrl = "https://integrate.api.nvidia.com/v1")
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(groq, named))
    }

    @Test fun differentUnknownEndpointsAndNamesCannotEstablishIndependence() {
        val first = PlatformV2(uid = "one", name = "Model A", model = "alias-a", apiUrl = "https://one.example/v1")
        val second = first.copy(uid = "two", name = "Model B", model = "alias-b", apiUrl = "https://two.example/v1", secretRef = "other-credential")
        assertNull(CanonicalModelIdentity.resolve(first).canonical)
        assertNull(CanonicalModelIdentity.resolve(second).canonical)
        assertEquals(ModelIndependence.UNKNOWN, CanonicalModelIdentity.independence(first, second))
        assertNotEquals(CanonicalModelIdentity.resolve(first).deployment, CanonicalModelIdentity.resolve(second).deployment)
        assertEquals(ModelIndependence.SAME, CanonicalModelIdentity.independence(first, first.copy(name = "Renamed")))
    }
}
