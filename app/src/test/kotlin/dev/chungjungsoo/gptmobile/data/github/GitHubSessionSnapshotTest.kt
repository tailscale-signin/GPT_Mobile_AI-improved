package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSessionSnapshotTest {
    @Test fun savedDisplayContextRetainsExactProfileRepositoryAndObservedHead() {
        val snapshot = GitHubSessionSnapshot(
            "connection", "account:stable-id", "coder", "repository-id", GitHubRepositoryContext("owner", "repository", "default"),
            "default", "a".repeat(40), 1234, "explicit-profile", 7, true
        )
        assertEquals(snapshot, Json.decodeFromString<GitHubSessionSnapshot>(Json.encodeToString(snapshot)))
        assertEquals(GitHubComposerReadiness.REFRESH_REQUIRED, GitHubComposerReadiness.valueOf("REFRESH_REQUIRED"))
    }

    @Test fun legacyLocalSnapshotDoesNotInventAProfileOrAuthority() {
        val snapshot = Json.decodeFromString<GitHubSessionSnapshot>("""{"connectionUid":"connection"}""")
        assertNull(snapshot.profileUid)
        assertNull(snapshot.repositoryId)
        assertNull(snapshot.observedHeadSha)
    }

    @Test fun switchingAwayAndBackRejectsBothOldSessionEpochs() {
        val epochs = GitHubSessionEpoch()
        val first = epochs.next()
        assertTrue(epochs.accepts(first))
        val second = epochs.next()
        val returned = epochs.next()
        assertFalse(epochs.accepts(first))
        assertFalse(epochs.accepts(second))
        assertTrue(epochs.accepts(returned))
    }
}
