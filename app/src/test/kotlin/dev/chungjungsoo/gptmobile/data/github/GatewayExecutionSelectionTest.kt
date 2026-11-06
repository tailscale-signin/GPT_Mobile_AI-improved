package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayExecutionSelectionTest {
    private val profile = PlatformV2(uid = "profile-a", name = "Chosen A", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080/v1", model = "A", secretRef = "vault-reference", temperature = .2f)

    @Test fun credentialsCanRefreshWithoutChangingSelectedIdentityOrPersistingSecrets() {
        val original = executionSelection(profile.copy(token = "first-secret"), null, "private-chat")
        val refreshed = executionSelection(profile.copy(token = "second-secret"), null, "private-chat")
        assertEquals(original, refreshed)
        assertFalse(Json.encodeToString(original).contains("secret"))
        assertEquals("vault-reference", original.credentialReference)
    }

    @Test fun modelEndpointAndSamplerEditsProduceNewSnapshotWhileOldSelectionSurvives() {
        val selected = executionSelection(profile, null, "private-chat")
        for (changed in listOf(profile.copy(model = "B"), profile.copy(apiUrl = "http://127.0.0.1:8081/v1"), profile.copy(temperature = .5f))) {
            assertNotEquals(selected.profileRevision, executionSelection(changed, null, "private-chat").profileRevision)
        }
        assertEquals("A", selected.requestedModelId)
        assertEquals("http://127.0.0.1:8080/v1", selected.configuredEndpoint)
    }

    @Test fun providerConnectionMismatchCannotSilentlySelectAnotherRoute() {
        val linked = profile.copy(providerConnectionUid = "connection-a")
        val other = ProviderConnection(uid = "connection-a", name = "Other route", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:9090/v1")
        assertThrows(IllegalStateException::class.java) { executionSelection(linked, other, "private-chat") }
        assertThrows(IllegalStateException::class.java) { executionSelection(linked, null, "private-chat") }
    }

    @Test fun normalizedEndpointKeepsHostPortAndEncodedPathIdentity() {
        assertEquals("http://example.test:8080/p%2Fath", selectedEndpoint("HTTP://EXAMPLE.TEST:8080/p%2Fath/"))
        assertThrows(IllegalArgumentException::class.java) { selectedEndpoint("http://user:secret@example.test") }
        assertThrows(IllegalArgumentException::class.java) { selectedEndpoint("http://example.test/v1?backend=other") }
        assertNotEquals(selectedEndpoint("http://localhost:8080"), selectedEndpoint("http://127.0.0.1:8080"))
    }

    @Test fun strictAdmissionRejectsServerModelOrProfileSubstitution() {
        val selection = executionSelection(profile, null, "private-chat")
        val result = admission(selection)
        result.verify(selection)
        assertThrows(IllegalStateException::class.java) {
            result.copy(binding = JsonObject(result.binding + ("requested_model_id" to JsonPrimitive("B")))).verify(selection)
        }
        assertThrows(IllegalStateException::class.java) {
            result.copy(capabilities = JsonObject(result.capabilities + ("reported_model_id" to JsonPrimitive("B")))).verify(selection)
        }
        assertEquals(result, Json.decodeFromString<GatewayExecutionAdmission>(Json.encodeToString(result)))
    }

    @Test fun unsupportedOllamaOptionsFailInsteadOfDroppingSelectedParameters() {
        assertThrows(IllegalStateException::class.java) {
            executionSelection(profile.copy(ollamaOptions = "{\"draft_model\":\"B\"}"), null, "private-chat")
        }
        val selected = executionSelection(profile.copy(ollamaOptions = "{\"num_ctx\":32768,\"seed\":7}"), null, "private-chat")
        assertEquals(JsonPrimitive(32768), selected.generationParameters["context_tokens"])
        assertEquals(JsonPrimitive(7), selected.generationParameters["seed"])
    }

    private fun admission(selected: GatewayExecutionSelection) = GatewayExecutionAdmission(
        binding = buildJsonObject {
            put("binding_id", "bind_" + "a".repeat(32))
            put("routing_policy", "SELECTED_ONLY")
            put("profile_uid", selected.profileUid)
            put("profile_revision", selected.profileRevision)
            put("provider_connection_id", selected.providerConnectionId)
            put("provider_connection_revision", selected.providerConnectionRevision)
            put("provider_kind", selected.providerKind)
            put("requested_model_id", selected.requestedModelId)
            put("privacy_scope", selected.privacyScope)
            put("tool_owner", selected.toolOwner)
            put("configured_endpoint_identity", selectionDigest(JsonPrimitive(selected.configuredEndpoint)))
            put("generation_parameters", selected.generationParameters)
        },
        capabilities = buildJsonObject {
            put("requested_model_id", selected.requestedModelId)
            put("reported_model_id", selected.requestedModelId)
        }
    )
}
