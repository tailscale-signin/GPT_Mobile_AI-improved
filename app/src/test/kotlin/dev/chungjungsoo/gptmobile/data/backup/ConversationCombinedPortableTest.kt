package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettingsSerializer
import dev.chungjungsoo.gptmobile.data.model.FeatureSettingsJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCombinedPortableTest {
    private val current = """{"edgeFades":false,"futureGlobal":{"keep":1},"conversationIdentities":{"4":"local"},"conversationCombined":{"4":{"effort":0,"revision":5}}}"""
    private fun snapshot(identity: String = "remote", effort: Int = 100) = ConversationCombinedPortableSnapshot(conversations = listOf(PortableConversationCombinedSettings(identity, 4, ConversationCombinedSettings(effort = effort))))

    @Test fun conversationsOnlyRestoreCarriesPolicyWithoutGenericPreferences() {
        val restored = ConversationCombinedPortable.restore(current, null, snapshot(), mapOf(4 to 4), setOf(4), false)
        val decoded = FeatureSettingsJson.decode(restored)
        assertEquals("remote", decoded.conversationIdentities[4])
        assertEquals(100, decoded.conversationCombined.getValue(4).effort)
        assertFalse(decoded.edgeFades)
        assertEquals(Json.parseToJsonElement("""{"keep":1}"""), Json.parseToJsonElement(restored).jsonObject["futureGlobal"])
    }

    @Test fun settingsOnlyNeverAttachesByReusedIntegerId() {
        val restored = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(current, "{}", snapshot(), null, setOf(4), true))
        assertEquals("local", restored.conversationIdentities[4])
        assertEquals(0, restored.conversationCombined.getValue(4).effort)
        assertEquals(100, restored.deferredConversationCombined.getValue("remote").effort)
    }

    @Test fun settingsOnlyMatchesStableIdentityAndInvalidatesStaleDraftRevision() {
        val matching = current.replace("\"4\":\"local\"", "\"99\":\"remote\"").replace("\"4\":{\"effort\"", "\"99\":{\"effort\"")
        val restored = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(matching, "{}", snapshot(), null, setOf(99), true))
        assertFalse(4 in restored.conversationCombined)
        assertEquals(100, restored.conversationCombined.getValue(99).effort)
        assertEquals(6L, restored.conversationCombined.getValue(99).revision)
        assertTrue(restored.deferredConversationCombined.isEmpty())
    }

    @Test fun explicitIdRemappingPreservesFutureSchemaPayload() {
        val future = Json.decodeFromString(ConversationCombinedSettingsSerializer, """{"schemaVersion":8,"effort":{"next":true},"opaque":[1,2]}""")
        val portable = snapshot().copy(conversations = listOf(PortableConversationCombinedSettings("future", 4, future)))
        val restored = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(current, null, portable, mapOf(4 to 12), setOf(12), false))
        assertEquals("future", restored.conversationIdentities[12])
        assertEquals(future.preservedFields, restored.conversationCombined.getValue(12).preservedFields)
        assertFalse(4 in restored.conversationCombined)
    }

    @Test fun legacyIntegerPoliciesAreDeferredUnlessTheirConversationsAreRestored() {
        val incoming = """{"conversationCombined":{"4":{"effort":75}}}"""
        val settingsOnly = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(current, incoming, null, null, setOf(4), true))
        assertEquals(0, settingsOnly.conversationCombined.getValue(4).effort)
        assertEquals(75, settingsOnly.deferredConversationCombined.values.single().effort)
        val full = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(current, incoming, null, mapOf(4 to 7), setOf(7), true))
        assertEquals(75, full.conversationCombined.getValue(7).effort)
    }

    @Test fun deferredSettingsAttachWhenTheirConversationArrivesLater() {
        val deferred = ConversationCombinedPortable.restore(current, null, snapshot(), null, setOf(4), true)
        val restored = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(deferred, null, snapshot(effort = 25), mapOf(4 to 9), setOf(9), false))
        assertEquals(100, restored.conversationCombined.getValue(9).effort)
        assertTrue(restored.deferredConversationCombined.isEmpty())
    }

    @Test fun legacyConversationsOnlyImportDoesNotBorrowLocalPolicyForSameInteger() {
        val restored = FeatureSettingsJson.decode(ConversationCombinedPortable.restore(current, null, null, mapOf(4 to 4), setOf(4), false))
        assertEquals(50, restored.conversationCombined.getValue(4).effort)
        assertTrue(restored.conversationIdentities.getValue(4) != "local")
    }

    @Test fun incomingGlobalChangesAndFutureFieldsSurviveScopedPolicyRestore() {
        val incoming = """{"edgeFades":true,"newGlobal":{"nested":9}}"""
        val restored = ConversationCombinedPortable.restore(current, incoming, snapshot(), null, setOf(4), true, baseRaw = incoming)
        assertTrue(FeatureSettingsJson.decode(restored).edgeFades)
        assertEquals(Json.parseToJsonElement("""{"nested":9}"""), Json.parseToJsonElement(restored).jsonObject["newGlobal"])
        assertEquals(0, FeatureSettingsJson.decode(restored).conversationCombined.getValue(4).effort)
    }

    @Test fun duplicateAndFuturePortableSchemasFailBeforeRestore() {
        assertTrue(runCatching { ConversationCombinedPortable.validate(snapshot().copy(schemaVersion = 2)) }.isFailure)
        val one = snapshot().conversations.single()
        assertTrue(runCatching { ConversationCombinedPortable.validate(snapshot().copy(conversations = listOf(one, one.copy(sourceChatId = 5)))) }.isFailure)
    }

    @Test fun exportPersistsStableIdentitiesAndRetainsBothControls() {
        val raw = ConversationCombinedPortable.ensureIdentities("""{"conversationCombined":{"8":{"collectionBias":75,"effort":100}}}""", setOf(8))
        val first = ConversationCombinedPortable.capture(raw, setOf(8))
        val second = ConversationCombinedPortable.capture(ConversationCombinedPortable.ensureIdentities(raw, setOf(8)), setOf(8))
        assertEquals(first, second)
        assertEquals(75, first.conversations.single().settings.collectionBias)
        assertEquals(100, first.conversations.single().settings.effort)
    }
}
