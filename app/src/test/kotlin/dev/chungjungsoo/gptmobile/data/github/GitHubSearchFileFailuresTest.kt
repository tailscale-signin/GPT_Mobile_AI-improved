package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSearchFileFailuresTest {
    private val context = GitHubTaskSnapshot("account", "connection", "chat", "task", "owner", "repo", "main", "a".repeat(40), "b".repeat(40))
    private val blob = "c".repeat(40)
    private val path = "src/PrivateFile.kt"

    @Test fun identityBindsTaskAndExactImmutableFileWithoutPresentationOrQueryState() {
        val identity = GitHubSearchFileFailures.identity(context, path, blob)
        val same = context.copy(owner = "OWNER", repo = "REPO", commitSha = context.commitSha.uppercase(), ref = "feature", evidenceHandles = listOf("saved-result"), updatedAt = context.updatedAt + 1)
        assertEquals(identity, GitHubSearchFileFailures.identity(same, path, blob.uppercase()))
        listOf(
            context.copy(accountId = "other"),
            context.copy(connectionUid = "other"),
            context.copy(conversationId = "other"),
            context.copy(taskId = "other"),
            context.copy(owner = "other"),
            context.copy(repo = "other"),
            context.copy(commitSha = "d".repeat(40))
        ).forEach { assertNotEquals(identity, GitHubSearchFileFailures.identity(it, path, blob)) }
        assertNotEquals(identity, GitHubSearchFileFailures.identity(context, path.lowercase(), blob))
        assertNotEquals(identity, GitHubSearchFileFailures.identity(context, path, "e".repeat(40)))
        assertTrue(identity.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun retriesSaturateAndCompletedMatchingClearsOnlyThatFile() {
        val first = GitHubSearchFileFailures.identity(context, path, blob)
        val second = GitHubSearchFileFailures.identity(context, "src/Other.kt", blob)
        var state: JsonObject? = null
        repeat(5) { state = GitHubSearchFileFailures.update(state, emptySet(), first) }
        state = GitHubSearchFileFailures.update(state, emptySet(), second)
        assertEquals(mapOf(first to 2, second to 1), GitHubSearchFileFailures.entries(state))
        state = GitHubSearchFileFailures.update(state, setOf(first), null)
        assertEquals(mapOf(second to 1), GitHubSearchFileFailures.entries(state))
        state = GitHubSearchFileFailures.update(state, emptySet(), first)
        assertEquals(1, GitHubSearchFileFailures.entries(state)[first])
        assertFalse(state.toString().contains(path))
        assertFalse(state.toString().contains(context.owner))
        assertFalse(state.toString().contains("query"))
    }

    @Test fun onlyMostRecentlyFailedFilesOccupyTheBoundedIndex() {
        val identities = (0..GitHubSearchFileFailures.MAX_ENTRIES).map { GitHubSearchFileFailures.identity(context, "src/File$it.kt", blob) }
        var state: JsonObject? = null
        identities.take(GitHubSearchFileFailures.MAX_ENTRIES).forEach { state = GitHubSearchFileFailures.update(state, emptySet(), it) }
        state = GitHubSearchFileFailures.update(state, emptySet(), identities.first())
        state = GitHubSearchFileFailures.update(state, emptySet(), identities.last())
        val entries = GitHubSearchFileFailures.entries(state)
        assertEquals(GitHubSearchFileFailures.MAX_ENTRIES, entries.size)
        assertEquals(2, entries[identities.first()])
        assertFalse(entries.containsKey(identities[1]))
        assertEquals(1, entries[identities.last()])
    }

    @Test fun malformedRetainedMetadataCannotSupplyAFailureOrUnboundedIdentities() {
        val identity = GitHubSearchFileFailures.identity(context, path, blob)
        val malformed = buildJsonObject {
            put(
                "entries",
                buildJsonObject {
                    put("private query text", 2)
                    put("d".repeat(64), -1)
                    put("e".repeat(64), GitHubSearchFileFailures.MAX_ATTEMPTS + 1)
                    put("f".repeat(64), JsonObject(emptyMap()))
                    put(identity, 1)
                }
            )
        }
        assertEquals(mapOf(identity to 1), GitHubSearchFileFailures.entries(malformed))
        assertEquals(emptyMap<String, Int>(), GitHubSearchFileFailures.entries(buildJsonObject { put("entries", JsonPrimitive("invalid")) }))
        val sanitized = GitHubSearchFileFailures.update(malformed, emptySet(), identity)
        assertEquals(mapOf(identity to 2), GitHubSearchFileFailures.entries(sanitized))
        assertFalse(sanitized.toString().contains("private query text"))
    }
}
