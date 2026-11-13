package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAssistantReferencesTest {
    private val context = GitHubAssistantReferenceContext("tailscale-signin", "GPT_Mobile_AI-improved", "https://github.com")

    @Test fun linksBranchCommitAndPullRequestReferencesToTheSelectedRepository() {
        val sha = "a".repeat(40)
        val shortSha = "abc1234"
        val rendered = linkifyGitHubAssistantReferences(
            "Branch `fix/voice-chat` contains commit $sha and commit $shortSha; PR #819 tracks it.",
            context
        )
        assertTrue(rendered, rendered.contains("[`fix/voice-chat`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/fix/voice-chat)"))
        assertTrue(rendered, rendered.contains("[$sha](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/$sha)"))
        assertTrue(rendered, rendered.contains("[$shortSha](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/$shortSha)"))
        assertTrue(rendered, rendered.contains("[#819](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/819)"))
    }

    @Test fun doesNotTreatUnlabeledHexadecimalValuesAsCommitReferences() {
        val source = "The main feature is ready; the numeric value 1234567 is only a counter."
        assertEquals(source, linkifyGitHubAssistantReferences(source, context))
    }

    @Test fun preservesExistingLinksUrlsAndFencedCode() {
        val source = "See [main](https://example.com), https://example.org/fix/change, and:\n```text\nPR #8 `fix/change`\n```"
        assertEquals(source, linkifyGitHubAssistantReferences(source, context))
    }

    @Test fun usesEnterpriseWebOriginAndOnlyLinksNamedOrRecognizedBranches() {
        val enterprise = context.copy(webOrigin = "https://git.example.org")
        val rendered = linkifyGitHubAssistantReferences("Use branch: support/custom-lifecycle, branch release/2.1 and inspect main branch. Issue #12 remains open.", enterprise)
        assertTrue(rendered, rendered.contains("https://git.example.org/tailscale-signin/GPT_Mobile_AI-improved/tree/support/custom-lifecycle"))
        assertTrue(rendered, rendered.contains("https://git.example.org/tailscale-signin/GPT_Mobile_AI-improved/tree/release/2.1"))
        assertTrue(rendered, rendered.contains("https://git.example.org/tailscale-signin/GPT_Mobile_AI-improved/tree/main"))
        assertTrue(rendered, rendered.contains("Issue #12"))
        assertTrue(rendered, !rendered.contains("/pull/12"))
    }

    @Test fun linksTheActiveCustomBranchAndOnlyTreatsNumberAsPullRequestWhenLabeled() {
        val selected = context.copy(activeBranch = "support/custom-lifecycle")
        val rendered = linkifyGitHubAssistantReferences(
            "Current: support/custom-lifecycle. Inspect `#12` and PR `#13`.",
            selected
        )
        assertTrue(rendered, rendered.contains("[support/custom-lifecycle](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/support/custom-lifecycle)"))
        assertTrue(rendered, rendered.contains("`#12`"))
        assertTrue(rendered, rendered.contains("[`#13`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/13)"))
    }

    @Test fun receiptRepositoryWinsForMultiRepositoryBranchCommitAndPullRequestLinks() {
        val scoped = context.withReceiptReferences(
            listOf(
                GitHubReferenceReceipt(
                    toolName = "github_pr__headers",
                    arguments = """{"owner":"tailscale-signin","repo":"GatewayTools","pull_number":44}""",
                    result = """{"html_url":"https://github.com/tailscale-signin/GatewayTools/pull/44","head_sha":"${"b".repeat(40)}","head_ref":"fix/agent-recovery"}"""
                )
            )
        )
        val sha = "b".repeat(40)
        val rendered = linkifyGitHubAssistantReferences("Branch fix/agent-recovery, commit $sha, and PR #44.", scoped)
        assertTrue(rendered, rendered.contains("https://github.com/tailscale-signin/GatewayTools/tree/fix/agent-recovery"))
        assertTrue(rendered, rendered.contains("https://github.com/tailscale-signin/GatewayTools/commit/$sha"))
        assertTrue(rendered, rendered.contains("https://github.com/tailscale-signin/GatewayTools/pull/44"))
        assertTrue(rendered, !rendered.contains("https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/44"))
    }

    @Test fun ambiguousReceiptReferenceIsNotMislinkedToTheSelectedRepository() {
        val branch = "fix/shared-name"
        val scoped = context.withReceiptReferences(
            listOf(
                GitHubReferenceReceipt("github_file", """{"owner":"org-one","repo":"repo-one","branch":"$branch"}""", "{}"),
                GitHubReferenceReceipt("github_file", """{"owner":"org-two","repo":"repo-two","branch":"$branch"}""", "{}")
            )
        )
        val rendered = linkifyGitHubAssistantReferences("Use branch $branch.", scoped)
        assertEquals("Use branch $branch.", rendered)
    }

    @Test fun appendedToolProgressParsesOnlyTheNewReceiptAndReusesUnchangedProjection() {
        var parses = 0
        val cache = GitHubReferenceProjectionCache { selected, receipt ->
            parses++
            selected.withReceiptReferences(listOf(receipt)).receiptUrlsByReference
        }
        val completed = (1..53).map { referenceReceipt(it) }
        val first = cache.project(context, completed)
        assertEquals(context.withReceiptReferences(completed), first)
        assertEquals(53, parses)

        assertSame(first, cache.project(context.copy(), completed.map { it.copy() }))
        assertEquals(53, parses)
        val continued = completed + referenceReceipt(54)
        val next = cache.project(context, continued)
        assertEquals(context.withReceiptReferences(continued), next)
        assertEquals(54, parses)

        val unchangedLinks = continued + GitHubReferenceReceipt("github_read", "{}", "{}")
        assertSame(next, cache.project(context, unchangedLinks))
        assertEquals(55, parses)
    }

    @Test fun changedOrRemovedReceiptsDoNotKeepOldReferenceBindings() {
        val cache = GitHubReferenceProjectionCache()
        val first = referenceReceipt(1)
        val changed = first.copy(result = """{"head_ref":"fix/replacement"}""")
        cache.project(context, listOf(first))
        val projected = cache.project(context, listOf(changed))
        assertEquals(context.withReceiptReferences(listOf(changed)), projected)
        assertFalse(projected.receiptUrlsByReference.containsKey("branch:fix/receipt-1"))
        assertTrue(projected.receiptUrlsByReference.containsKey("branch:fix/replacement"))
        assertTrue(cache.project(context, emptyList()).receiptUrlsByReference.isEmpty())
    }

    @Test fun incrementalProjectionPreservesAmbiguityAndClearsItWhenConflictingReceiptLeaves() {
        val cache = GitHubReferenceProjectionCache()
        val first = GitHubReferenceReceipt("github_file", """{"owner":"one","repo":"first","branch":"fix/shared"}""", "{}")
        val conflicting = first.copy(arguments = """{"owner":"two","repo":"second","branch":"fix/shared"}""")
        cache.project(context, listOf(first))
        val ambiguous = cache.project(context, listOf(first, conflicting, first.copy()))
        assertTrue(ambiguous.receiptUrlsByReference.containsKey("branch:fix/shared"))
        assertNull(ambiguous.receiptUrlsByReference["branch:fix/shared"])
        assertEquals("Use branch fix/shared.", linkifyGitHubAssistantReferences("Use branch fix/shared.", ambiguous))

        val reconciled = cache.project(context, listOf(first))
        assertEquals("https://github.com/one/first", reconciled.receiptUrlsByReference["branch:fix/shared"])
        assertEquals(context.withReceiptReferences(listOf(first)), reconciled)
    }

    @Test fun repositoryAndOriginChangesInvalidateFallbackBindings() {
        var parses = 0
        val cache = GitHubReferenceProjectionCache { selected, receipt ->
            parses++
            selected.withReceiptReferences(listOf(receipt)).receiptUrlsByReference
        }
        val receipts = listOf(GitHubReferenceReceipt("github_file", """{"branch":"main"}""", "{}"))
        cache.project(context, receipts)
        val changedRepository = context.copy(owner = "other", repository = "project")
        val changedOrigin = changedRepository.copy(webOrigin = "https://git.example.org", activeBranch = "main")
        assertEquals("https://github.com/other/project", cache.project(changedRepository, receipts).receiptUrlsByReference["branch:main"])
        assertEquals("https://git.example.org/other/project", cache.project(changedOrigin, receipts).receiptUrlsByReference["branch:main"])
        assertEquals(3, parses)
    }

    @Test fun projectionRetainsOnlyTheCurrentBoundedReceiptWindow() {
        var parses = 0
        val cache = GitHubReferenceProjectionCache { selected, receipt ->
            parses++
            selected.withReceiptReferences(listOf(receipt)).receiptUrlsByReference
        }
        val history = (1..130).map { referenceReceipt(it) }
        val projected = cache.project(context, history)
        assertEquals(128, parses)
        assertEquals(context.withReceiptReferences(history), projected)
        assertFalse(projected.receiptUrlsByReference.containsKey("pr:1"))
        cache.project(context, emptyList())
        cache.project(context, listOf(history.last()))
        assertEquals(129, parses)
    }

    @Test fun oversizedAndMalformedReceiptsKeepExistingProjectionBounds() {
        val receipts = listOf(
            GitHubReferenceReceipt("github_file", "x".repeat(8_193), "{}"),
            GitHubReferenceReceipt("github_file", "{}", "x".repeat(32_769)),
            GitHubReferenceReceipt("github_file", "not json", "{}"),
            referenceReceipt(4)
        )
        assertEquals(receipts.takeLast(2), boundedGitHubReferenceReceipts(receipts))
        assertEquals(context.withReceiptReferences(receipts), GitHubReferenceProjectionCache().project(context, receipts))
    }

    @Test fun ignoredOversizedReceiptsDoNotBringOlderReceiptsBackIntoTheWindow() {
        val old = referenceReceipt(1)
        val ignored = GitHubReferenceReceipt("github_file", "{}", "x".repeat(32_769))
        val history = listOf(old) + List(128) { ignored }
        assertTrue(boundedGitHubReferenceReceipts(history).isEmpty())
        assertEquals(context.withReceiptReferences(history), GitHubReferenceProjectionCache().project(context, history))
    }

    @Test fun completedBackgroundProjectionCannotBindANewerResponseOrRepository() {
        val receipts = listOf(referenceReceipt(1))
        val request = GitHubReceiptReferenceRequest(context, receipts)
        val projected = context.withReceiptReferences(receipts)
        val prepared = PreparedGitHubReceiptReferences(request, projected)
        assertSame(projected, prepared.forRequest(request.copy(receipts = receipts.map { it.copy() })))
        assertNull(prepared.forRequest(request.copy(context = context.copy(repository = "another"))))
        assertNull(prepared.forRequest(request.copy(receipts = receipts + referenceReceipt(2))))
        assertNull(prepared.forRequest(request.copy(context = null)))
    }

    private fun referenceReceipt(number: Int) = GitHubReferenceReceipt(
        toolName = "github_pr",
        arguments = """{"owner":"sample","repo":"project","pull_number":$number}""",
        result = """{"head_ref":"fix/receipt-$number"}"""
    )
}
