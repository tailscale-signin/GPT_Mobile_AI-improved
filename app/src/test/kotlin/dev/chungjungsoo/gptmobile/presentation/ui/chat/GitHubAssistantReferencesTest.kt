package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
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
}
