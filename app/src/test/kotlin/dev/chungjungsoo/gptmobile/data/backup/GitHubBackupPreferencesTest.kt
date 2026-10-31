package dev.chungjungsoo.gptmobile.data.backup

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBackupPreferencesTest {
    private fun prefs(raw: String) = mapOf(ConversationCombinedPortable.FEATURE_KEY to BackupValue("string", raw))
    private val incoming = prefs("""{"conversationGitHub":{"12":{"connectionUid":"github","owner":"owner","repo":"repo","ref":"main","commitSha":"${"a".repeat(40)}","mainProfileUid":"model","autoApproveTools":true,"observedAtMillis":123}},"githubMainProfiles":{"github":"model"},"githubPrepareIndex":["github"]}""")
    private val snapshot = ConversationCombinedPortableSnapshot(conversations = listOf(PortableConversationCombinedSettings("identity", 12, ConversationCombinedSettings())))

    @Test fun settingsOnlyRestoreMatchesIdentityAndResetsWriteApprovalAndFreshness() {
        val current = prefs("""{"conversationIdentities":{"24":"identity","12":"different"}}""")
        val restored = GitHubBackupPreferences.restore(current, current, incoming, snapshot, false, true, setOf(12, 24), setOf("github"), setOf("model"))
        val root = Json.parseToJsonElement(restored.getValue(ConversationCombinedPortable.FEATURE_KEY).value).jsonObject
        val contexts = root.getValue("conversationGitHub").jsonObject
        assertEquals(setOf("24"), contexts.keys)
        assertFalse(contexts.getValue("24").jsonObject.getValue("autoApproveTools").toString().toBoolean())
        assertEquals("null", contexts.getValue("24").jsonObject.getValue("observedAtMillis").toString())
        assertTrue(root.getValue("githubMainProfiles").toString().contains("model"))
    }

    @Test fun restoredChatsAdoptWorkspacesButMissingConnectionsAndProfilesDoNot() {
        val full = GitHubBackupPreferences.restore(emptyMap(), emptyMap(), incoming, snapshot, true, true, setOf(12), setOf("github"), setOf("model"))
        assertTrue(full.values.single().value.contains("owner"))
        val missing = GitHubBackupPreferences.restore(emptyMap(), emptyMap(), incoming, snapshot, true, true, setOf(12), emptySet(), setOf("model"))
        assertFalse(missing.values.single().value.contains("owner"))
        val collision = GitHubBackupPreferences.restore(emptyMap(), emptyMap(), incoming, snapshot, false, true, setOf(12), setOf("github"), setOf("model"))
        assertFalse(collision.values.single().value.contains("owner"))
    }
}
