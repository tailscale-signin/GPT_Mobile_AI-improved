package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchResumePolicyTest {
    private val chat = Json.parseToJsonElement("""{"enabledToolIds":["github"],"github":{"connectionUid":"account","owner":"owner","repo":"repo","ref":"main","mainProfileUid":"selected","autoApproveTools":false,"commitSha":"old","stagedFileCount":0,"recentCommits":[],"observedAtMillis":10}}""") as JsonObject

    private fun changedGitHub(field: String, value: String) = JsonObject(chat + ("github" to JsonObject((chat.getValue("github") as JsonObject) + (field to JsonPrimitive(value)))))

    @Test fun refreshedRepositoryObservationsDoNotInvalidateTheSameRecoveryAction() {
        val accepted = ResearchResumePolicy.revision("configured-policy", chat)
        listOf("commitSha", "stagedFileCount", "recentCommits", "observedAtMillis").forEach { field ->
            assertEquals(accepted, ResearchResumePolicy.revision("configured-policy", changedGitHub(field, "new observation")))
        }
    }

    @Test fun destinationPermissionsAndConfiguredPolicyRemainPartOfTheRecoveryIdentity() {
        val accepted = ResearchResumePolicy.revision("configured-policy", chat)
        listOf("connectionUid", "owner", "repo", "ref", "mainProfileUid", "autoApproveTools").forEach { field ->
            assertNotEquals(accepted, ResearchResumePolicy.revision("configured-policy", changedGitHub(field, "changed")))
        }
        assertNotEquals(accepted, ResearchResumePolicy.revision("changed-grants", chat))
        assertNotEquals(accepted, ResearchResumePolicy.revision("configured-policy", JsonObject(chat + ("allToolsDisabled" to JsonPrimitive(true)))))
    }

    @Test fun legacyMigrationNeedsExactProofAndCanNeverOverrideAnEstablishedSemanticPolicy() {
        val old = "a".repeat(64)
        val current = ResearchResumePolicy.revision("configured-policy", chat)
        assertTrue(ResearchResumePolicy.mayMigrate(old, current, old))
        assertFalse(ResearchResumePolicy.mayMigrate(old, current, null))
        assertFalse(ResearchResumePolicy.mayMigrate(old, current, "b".repeat(64)))
        assertFalse(ResearchResumePolicy.mayMigrate(current, ResearchResumePolicy.revision("revoked", chat), old))
        assertFalse(ResearchResumePolicy.mayMigrate(old, "unversioned", old))
    }
}
