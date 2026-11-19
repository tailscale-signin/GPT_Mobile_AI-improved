package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.openrouter.account.OpenRouterIdentity
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterWorkspacePrivacyTest {
    private val hash = OpenRouterIdentity.digest("synthetic-inference-key")
    private val workspace = "11111111-2222-3333-4444-555555555555"
    private val otherWorkspace = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val now = Instant.parse("2026-11-19T00:00:00Z").toEpochMilli()
    private val binding = OpenRouterWorkspacePrivacy.KeyBinding(hash, workspace)
    private val controls = OpenRouterWorkspacePrivacy.strictPatch.keys

    private fun envelope(data: JsonElement) = JsonObject(mapOf("data" to data))

    private fun keyData() = JsonObject(
        mapOf(
            "hash" to JsonPrimitive(hash),
            "disabled" to JsonPrimitive(false),
            "workspace_id" to JsonPrimitive(workspace),
            "expires_at" to JsonNull
        )
    )

    private fun workspaceData() = JsonObject(
        OpenRouterWorkspacePrivacy.strictPatch + mapOf(
            "id" to JsonPrimitive(workspace),
            "name" to JsonPrimitive("Personal workspace")
        )
    )

    @Test
    fun exactActiveKeyWithExplicitNoExpiryBindsToObservedWorkspace() {
        assertEquals(binding, OpenRouterWorkspacePrivacy.parseKey(envelope(keyData()), hash, now))
    }

    @Test
    fun onlyTheExactCredentialDigestIdentifiesTheKey() {
        val otherHash = OpenRouterIdentity.digest("different-inference-key")
        val misleading = JsonObject(keyData() + mapOf("hash" to JsonPrimitive(otherHash), "label" to JsonPrimitive(hash)))
        assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(misleading), hash, now))
        assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(keyData()), otherHash, now))
        listOf("", "key-label", "a".repeat(63), "A".repeat(64)).forEach { invalid ->
            assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(JsonObject(keyData() + ("hash" to JsonPrimitive(invalid)))), invalid, now))
        }
    }

    @Test
    fun missingNullStringOrEnabledKeyStateCannotQualify() {
        listOf(null, JsonNull, JsonPrimitive("false"), JsonPrimitive(true), JsonPrimitive(0)).forEach { invalid ->
            val fields = keyData() - "disabled" + if (invalid == null) emptyMap() else mapOf("disabled" to invalid)
            assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(JsonObject(fields)), hash, now))
        }
    }

    @Test
    fun keyRequiresAnExplicitValidWorkspaceIdentity() {
        listOf(null, JsonNull, JsonPrimitive("workspace-label"), JsonPrimitive(1), JsonPrimitive("../workspaces/other")).forEach { invalid ->
            val fields = keyData() - "workspace_id" + if (invalid == null) emptyMap() else mapOf("workspace_id" to invalid)
            assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(JsonObject(fields)), hash, now))
        }
    }

    @Test
    fun expiredMalformedOrUnspecifiedExpiryCannotQualify() {
        listOf(
            null,
            JsonPrimitive(""),
            JsonPrimitive(false),
            JsonPrimitive(0),
            JsonPrimitive("invalid-time"),
            JsonPrimitive("2026-11-18T23:59:59Z"),
            JsonPrimitive("2026-11-19T00:00:00Z")
        ).forEach { invalid ->
            val fields = keyData() - "expires_at" + if (invalid == null) emptyMap() else mapOf("expires_at" to invalid)
            assertNull(OpenRouterWorkspacePrivacy.parseKey(envelope(JsonObject(fields)), hash, now))
        }
        val future = JsonObject(keyData() + ("expires_at" to JsonPrimitive("2026-11-19T00:00:00.001Z")))
        assertEquals(binding, OpenRouterWorkspacePrivacy.parseKey(envelope(future), hash, now))
    }

    @Test
    fun malformedEnvelopesNeverProduceAccountEvidence() {
        listOf(JsonObject(emptyMap()), envelope(JsonNull), envelope(JsonArray(emptyList())), envelope(JsonPrimitive("data"))).forEach { malformed ->
            assertNull(OpenRouterWorkspacePrivacy.parseKey(malformed, hash, now))
            assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(malformed, workspace))
        }
    }

    @Test
    fun exactWorkspaceWithAllControlsDisabledIsStrict() {
        val parsed = OpenRouterWorkspacePrivacy.parseWorkspace(envelope(workspaceData()), workspace)
        assertNotNull(parsed)
        assertEquals(workspace, parsed!!.workspaceId)
        assertEquals("Personal workspace", parsed.name)
        assertTrue(parsed.strict)
    }

    @Test
    fun enabledControlIsObservedAndRequiresTightening() {
        controls.forEach { control ->
            val parsed = OpenRouterWorkspacePrivacy.parseWorkspace(envelope(JsonObject(workspaceData() + (control to JsonPrimitive(true)))), workspace)
            assertNotNull(parsed)
            assertFalse(parsed!!.strict)
        }
    }

    @Test
    fun everyControlRequiresALiteralBooleanRatherThanAnAssumedDefault() {
        controls.forEach { control ->
            listOf(null, JsonNull, JsonPrimitive("false"), JsonPrimitive("true"), JsonPrimitive(0)).forEach { invalid ->
                val fields = workspaceData() - control + if (invalid == null) emptyMap() else mapOf(control to invalid)
                assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(envelope(JsonObject(fields)), workspace))
            }
        }
    }

    @Test
    fun anotherWorkspaceCannotSatisfyTheSelectedKeyEvenWithMatchingNameAndSettings() {
        assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(envelope(workspaceData()), otherWorkspace))
        val other = JsonObject(workspaceData() + ("id" to JsonPrimitive(otherWorkspace)))
        assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(envelope(other), workspace))
        assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(envelope(workspaceData()), "Personal workspace"))
    }

    @Test
    fun malformedOrUnboundedWorkspaceNamesAreRejected() {
        listOf(null, JsonNull, JsonPrimitive(false), JsonPrimitive(""), JsonPrimitive("   "), JsonPrimitive("name\nother"), JsonPrimitive("a".repeat(161))).forEach { invalid ->
            val fields = workspaceData() - "name" + if (invalid == null) emptyMap() else mapOf("name" to invalid)
            assertNull(OpenRouterWorkspacePrivacy.parseWorkspace(envelope(JsonObject(fields)), workspace))
        }
    }

    @Test
    fun strictPatchContainsOnlyThreePrivacyDisables() {
        assertEquals(
            setOf("is_observability_io_logging_enabled", "is_data_discount_logging_enabled", "is_observability_broadcast_enabled"),
            OpenRouterWorkspacePrivacy.strictPatch.keys
        )
        assertTrue(OpenRouterWorkspacePrivacy.strictPatch.values.all { it == JsonPrimitive(false) })
    }

    @Test
    fun pendingMutationIsBoundToTheExactKeyWorkspaceAndIntendedValues() {
        val patch = OpenRouterWorkspacePrivacy.strictPatch
        val identity = OpenRouterWorkspacePrivacy.mutationIdentity(binding, patch)
        assertTrue(identity.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(identity, OpenRouterWorkspacePrivacy.mutationIdentity(binding.copy(hash = OpenRouterIdentity.digest("other-account-key")), patch))
        assertNotEquals(identity, OpenRouterWorkspacePrivacy.mutationIdentity(binding.copy(workspaceId = otherWorkspace), patch))
        assertNotEquals(identity, OpenRouterWorkspacePrivacy.mutationIdentity(binding, JsonObject(patch - controls.first())))
        assertNotEquals(identity, OpenRouterWorkspacePrivacy.mutationIdentity(binding, JsonObject(patch + (controls.first() to JsonPrimitive(true)))))
        assertNotEquals(identity, OpenRouterWorkspacePrivacy.mutationIdentity(binding, JsonObject(patch + (controls.first() to JsonPrimitive("false")))))
    }

    @Test
    fun reorderedFieldsReconcileTheSameMutation() {
        val patch = OpenRouterWorkspacePrivacy.strictPatch
        val reordered = JsonObject(patch.entries.reversed().associate { it.key to it.value })
        assertEquals(OpenRouterWorkspacePrivacy.mutationIdentity(binding, patch), OpenRouterWorkspacePrivacy.mutationIdentity(binding, reordered))
    }

    @Test
    fun malformedBindingCannotIssueAMutationReceipt() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenRouterWorkspacePrivacy.mutationIdentity(binding.copy(hash = "label"), OpenRouterWorkspacePrivacy.strictPatch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenRouterWorkspacePrivacy.mutationIdentity(binding.copy(workspaceId = "workspace-name"), OpenRouterWorkspacePrivacy.strictPatch)
        }
    }
}
