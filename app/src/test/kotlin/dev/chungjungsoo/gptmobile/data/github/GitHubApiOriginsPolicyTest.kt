package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiOriginsPolicyTest {
    @Test fun configuredEnterpriseOriginsRemainSeparateFromGitHubCom() {
        assertEquals(GitHubApiOrigins.GITHUB_COM, GitHubApiOrigins.fromEndpoint(null))
        assertEquals(GitHubApiOrigins.GITHUB_COM, GitHubApiOrigins.fromEndpoint("https://github.com"))
        val enterprise = GitHubApiOrigins.fromEndpoint("https://git.example.org/api/v3/")
        assertEquals("https://git.example.org/api/v3", enterprise.rest)
        assertEquals("https://git.example.org/api/graphql", enterprise.graphQl)
        assertEquals("https://git.example.org", enterprise.authorization)
        assertEquals("https://git.example.org:8443/api/v3", GitHubApiOrigins.fromEndpoint("https://git.example.org:8443").rest)
        assertTrue(enterprise.acceptsApiUrl("https://git.example.org/api/v3/repos/a/b?page=2"))
        for (url in listOf("https://evil.example/api/v3/repos/a/b", "https://git.example.org/api/v30/user", "https://user:password@git.example.org/api/v3/user", "https://git.example.org/api/v3/user#secret")) assertFalse(enterprise.acceptsApiUrl(url))
        for (url in listOf("http://git.example.org", "https://git.example.org/repository", "https://git.example.org?next=evil", "https://secret@git.example.org", "https://git.example.org:0", "https://git.example.org:65536", "https://github.com:8443")) assertTrue(runCatching { GitHubApiOrigins.fromEndpoint(url) }.isFailure)
    }
}
