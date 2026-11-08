package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubActionIdentityTest {
    private val arguments = buildJsonObject {
        put("action", "delete_fixture_branch")
        put("branch", "codex-self-test/owned")
        put("origin_head_sha", "a".repeat(40))
        put("expected_head_sha", "b".repeat(40))
    }

    @Test fun repositoryEnrichmentAndObjectOrderShareTheExactActionIdentity() {
        val enriched = GitHubActionIdentity.bind(arguments, GitHubRepositoryContext("Owner", "Repo", "main"))
        val original = GitHubActionIdentity.digest("account-and-connection-scope", "owner/repo", "github_commit__selected", arguments)
        assertEquals(original, GitHubActionIdentity.digest("account-and-connection-scope", "Owner/Repo", "github_commit__selected", JsonObject(enriched.entries.reversed().associate { it.toPair() })))
    }

    @Test fun authorityResourcesRevisionsContentAndLiveIntentRemainDifferentIdentities() {
        val original = GitHubActionIdentity.digest("scope", "owner/repo", "github_commit__selected", arguments)
        for ((field, value) in mapOf("branch" to "another-branch", "expected_head_sha" to "c".repeat(40), "origin_head_sha" to "d".repeat(40), "content" to "different-content", "verify_live" to "true")) {
            assertNotEquals(original, GitHubActionIdentity.digest("scope", "owner/repo", "github_commit__selected", JsonObject(arguments + (field to JsonPrimitive(value)))))
        }
        assertNotEquals(original, GitHubActionIdentity.digest("other-account-or-connection", "owner/repo", "github_commit__selected", arguments))
        assertNotEquals(original, GitHubActionIdentity.digest("scope", "owner/repo", "github_commit__other", arguments))
        assertNotEquals(original, GitHubActionIdentity.digest("scope", "owner/other", "github_commit__selected", arguments))
        assertNull(GitHubActionIdentity.digest("scope", "owner/repo", "github_commit__selected", JsonObject(arguments + ("repo" to JsonPrimitive("other")))))
        assertFalse(
            GitHubActionIdentity.matches(
                "github_commit__other",
                arguments,
                buildJsonObject {
                    put("binding", "scope")
                    put("repository", "owner/repo")
                    put("tool", "github_commit__selected")
                    put("arguments", arguments)
                }
            )
        )
    }
}
