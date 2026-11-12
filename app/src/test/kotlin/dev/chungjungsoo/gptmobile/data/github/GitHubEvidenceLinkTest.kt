package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEvidenceLinkTest {
    private val commit = "a".repeat(40)
    private val blob = "b".repeat(40)
    private val tree = "c".repeat(40)
    private val base = "https://github.com/owner/repo"
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun sourceBlobLinksUsePinnedCommitAndPathInsteadOfTheBlobAsACommit() {
        val receipt = json("""{"tool":"github_read","arguments":{"commit_sha":"$commit"},"result":{"commit_sha":"$commit","tree_sha":"$tree","items":[{"path":"src/Some File.kt","sha":"$blob","expected_blob_sha":"$blob","start_line":10,"end_line":20}]}}""")
        assertEquals("$base/blob/$commit/src/Some%20File.kt#L10-L20", GitHubEvidenceLink.url(base, "/items/0/sha", blob, receipt))
        assertEquals("$base/blob/$commit/src/Some%20File.kt#L10-L20", GitHubEvidenceLink.url(base, "/items/0/path", "src/Some File.kt", receipt))
        assertEquals("$base/commit/$commit", GitHubEvidenceLink.url(base, "/commit_sha", commit, receipt))
        assertNull(GitHubEvidenceLink.url(base, "/tree_sha", tree, receipt))
    }

    @Test fun unknownHashesAndUnsafePathsNeverBecomeCommitLinks() {
        val receipt = json("""{"tool":"github_read","result":{"sha":"$blob","digest_sha256":"$blob","items":[{"path":"../private.kt","sha":"$blob","commit_sha":"$commit"}]}}""")
        assertNull(GitHubEvidenceLink.url(base, "/sha", blob, receipt))
        assertNull(GitHubEvidenceLink.url(base, "/digest_sha256", blob, receipt))
        assertNull(GitHubEvidenceLink.url(base, "/items/0/sha", blob, receipt))
    }

    @Test fun commitAndPullRequestLinksRequireTheObservedObjectKind() {
        val receipt = json("""{"tool":"github_context","arguments":{"action":"commits"},"result":{"items":[{"sha":"$commit","commit":{"tree":{"sha":"$tree"}}}]}}""")
        assertEquals("$base/commit/$commit", GitHubEvidenceLink.url(base, "/items/0/sha", commit, receipt))
        assertNull(GitHubEvidenceLink.url(base, "/items/0/commit/tree/sha", tree, receipt))
        val pr = json("""{"tool":"github_pr","result":{"pull_requests":[{"number":42,"head":{"sha":"$commit"}}],"open_pull_requests":2}}""")
        assertEquals("$base/pull/42", GitHubEvidenceLink.url(base, "/pull_requests/0/number", "42", pr))
        assertEquals("$base/commit/$commit", GitHubEvidenceLink.url(base, "/pull_requests/0/head/sha", commit, pr))
        assertNull(GitHubEvidenceLink.url(base, "/open_pull_requests", "2", pr))
    }

    @Test fun verifiedReportDoesNotRenderAFileHashAsACommit() {
        val receipt = json("""{"repository":"owner/repo","tool":"github_read","trusted":true,"success":true,"verification_eligible":true,"observed_at_ms":1000,"epoch":0,"result":{"commit_sha":"$commit","items":[{"path":"src/Main.kt","sha":"$blob","commit_sha":"$commit"}]}}""")
        val claims = json("""{"claims":[{"kind":"fact","receipt_id":"source","pointer":"/items/0/sha","value":"$blob"}]}""")
        val text = GitHubEvidencePolicy.render(claims, mapOf("source" to receipt), 1000L, 0L).first
        assertTrue(text.contains("[$blob]($base/blob/$commit/src/Main.kt)"))
        assertFalse(text.contains("/commit/$blob"))
    }
}
