package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTargetPolicyTest {
    @Test fun explicitGitHubUrlsKeepTheRepositoryWhileFilePathsDoNotCreateSourceGrants() {
        val request = "Inspect app/src/Main.kt and docs/guides/setup.md, then compare https://github.com/Upstream/Project/tree/main/app/src and `other/repository`."
        assertEquals(setOf("upstream/project", "other/repository"), GitHubTargetPolicy.readSources(request))
        assertTrue(GitHubTargetPolicy.readSources("Read ./app/src/Main.kt, /usr/local/bin, and C:/workspace/project/file.kt").isEmpty())
        assertTrue(GitHubTargetPolicy.readSources("Use https://example.test/upstream/project and https://github.com.evil.test/other/repository").isEmpty())
    }

    @Test fun sourceReadGrantIsExactAndDoesNotAuthorizeWrites() {
        val policy = GitHubTargetPolicy("Read https://github.com/UPSTREAM/Project/blob/main/README.md")
        assertTrue(policy.suppliedRead("get_file_contents", "upstream/project"))
        assertTrue(policy.suppliedRead("resolve_context", "Upstream/Project"))
        assertFalse(policy.suppliedRead("get_file_contents", "upstream/project-copy"))
        assertFalse(policy.suppliedRead("update_file", "upstream/project"))
        assertFalse(policy.suppliedRead("create_branch", "upstream/project"))
    }
}
