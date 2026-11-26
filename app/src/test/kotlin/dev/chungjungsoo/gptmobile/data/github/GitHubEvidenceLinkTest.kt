package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun fileCitationsForClippedReadsPointOnlyToCapturedLines() {
        fun link(extra: String): String? {
            val receipt = json("""{"tool":"github_read","result":{"commit_sha":"$commit","items":[{"path":"src/File.kt","sha":"$blob","start_line":1400,"end_line":1431,"truncated":true$extra}]}}""")
            return GitHubEvidenceLink.url(base, "/items/0/path", "src/File.kt", receipt)
        }
        val file = "$base/blob/$commit/src/File.kt"
        assertEquals("$file#L1402-L1408", link(""","captured_start_line":1402,"captured_end_line":1408"""))
        assertEquals(file, link(""","content":"" """))
        assertEquals(file, link(""","captured_start_line":1402,"captured_end_line":1500"""))
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

    @Test fun verifiedSourceExcerptsLinkToTheConfiguredHostAtTheirExactLines() {
        val data = json("""{"commit_sha":"$commit","items":[{"path":"src/Some File.kt","sha":"$blob","start_line":8,"end_line":9,"content":"one\ntwo","html_url":"https://other.example/decoy"}]}""")
        val claims = json("""{"claims":[{"kind":"excerpt","receipt_id":"source","pointer":"/items/0/content#L8-L9","value":"one\ntwo"}]}""")
        val raw = JsonObject(
            json("""{"repository":"owner/repo","receipt_id":"source","tool":"github_read__selected","call_id":"read-a","trusted":true,"success":true,"verification_eligible":true,"immutable":true,"epoch":0}""") +
                mapOf("result" to data, "result_sha256" to JsonPrimitive(GitHubRepositoryStateStore.digest(data.toString())))
        )
        fun render(receipt: JsonObject) = GitHubEvidencePolicy.render(claims, mapOf("source" to receipt), 1000L, 0L).first
        for (origin in listOf("https://github.com", "https://git.example:8443")) {
            val current = JsonObject(raw + ("web_origin" to JsonPrimitive(origin)))
            val expected = "[src/Some File.kt]($origin/owner/repo/blob/$commit/src/Some%20File.kt#L8-L9)"
            for (receipt in listOf(current, GitHubEvidenceProjection.receipt(current))) {
                assertTrue(render(receipt), expected in render(receipt))
                assertFalse(render(receipt).contains("https://other.example"))
            }
            val unbound = JsonObject(current - "call_id")
            assertFalse(render(unbound).contains("/blob/"))
        }
        assertFalse(render(raw).contains("/blob/"))
    }

    @Test fun excerptCitationsRequireAnExactOriginSafePathAndOrderedLineRange() {
        val binding = json("""{"commit_sha":"$commit","start_line":8,"end_line":8}""")
        val scope = json("""{"repository":"owner/repo","web_origin":"https://github.com"}""")
        assertEquals("$base/blob/$commit/src/A.kt#L8", GitHubEvidenceLink.excerptUrl(scope, "src/A.kt", binding))
        assertNull(GitHubEvidenceLink.excerptUrl(scope, "../A.kt", binding))
        assertNull(GitHubEvidenceLink.excerptUrl(scope, "src/A.kt", JsonObject(binding + ("end_line" to JsonPrimitive(7)))))
        assertNull(GitHubEvidenceLink.excerptUrl(JsonObject(scope + ("web_origin" to JsonPrimitive("https://github.com@other.example"))), "a", binding))
    }

    @Test fun verifiedReportDoesNotRenderAFileHashAsACommit() {
        val receipt = json("""{"repository":"owner/repo","tool":"github_read","trusted":true,"success":true,"verification_eligible":true,"observed_at_ms":1000,"epoch":0,"result":{"commit_sha":"$commit","items":[{"path":"src/Main.kt","sha":"$blob","commit_sha":"$commit"}]}}""")
        val claims = json("""{"claims":[{"kind":"fact","receipt_id":"source","pointer":"/items/0/sha","value":"$blob"}]}""")
        val text = GitHubEvidencePolicy.render(claims, mapOf("source" to receipt), 1000L, 0L).first
        assertTrue(text.contains("[$blob]($base/blob/$commit/src/Main.kt)"))
        assertFalse(text.contains("/commit/$blob"))
    }
}
